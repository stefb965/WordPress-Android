package org.wordpress.android.ui.media;

import java.util.ArrayList;
import java.util.List;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AbsListView.RecyclerListener;
import android.widget.AdapterView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;

import com.actionbarsherlock.internal.widget.IcsAdapterView;
import com.actionbarsherlock.internal.widget.IcsAdapterView.OnItemSelectedListener;
import com.actionbarsherlock.internal.widget.IcsSpinner;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageLoader.ImageContainer;
import com.android.volley.toolbox.ImageLoader.ImageListener;
import com.android.volley.toolbox.NetworkImageView;

import org.xmlrpc.android.ApiHelper;
import org.xmlrpc.android.ApiHelper.SyncMediaLibraryTask.Callback;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.models.Blog;
import org.wordpress.android.ui.CheckableFrameLayout;
import org.wordpress.android.ui.MultiSelectGridView;
import org.wordpress.android.ui.MultiSelectGridView.MultiSelectListener;
import org.wordpress.android.ui.WPActionBarActivity;
import org.wordpress.android.ui.media.MediaGridAdapter.MediaGridAdapterCallback;

public class MediaGridFragment extends Fragment implements OnItemClickListener, MediaGridAdapterCallback, RecyclerListener, MultiSelectListener {
    
    private static final int MIN_REFERSH_INTERVAL_MS = 10 * 1000;

    private static final String BUNDLE_CHECKED_STATES = "BUNDLE_CHECKED_STATES";

    private Cursor mCursor;
    private Filter mFilter = Filter.ALL;
    private String[] mFiltersText;
    
    private MultiSelectGridView mGridView;
    private MediaGridAdapter mGridAdapter;
    private MediaGridListener mListener;
    private long mLastRefreshTime;
    
    private boolean mIsRefreshing = false;
    
    private ArrayList<String> mCheckedItems;
    
    private IcsSpinner mSpinner;
    private View mSpinnerContainer;

    
    public interface MediaGridListener {
        public void onMediaItemListDownloadStart();
        public void onMediaItemListDownloaded();
        public void onMediaItemSelected(String mediaId);
        public void onMultiSelectChange(int count);
    }
    
    public enum Filter {
        ALL, IMAGES, UNATTACHED;
        
        public static Filter getFilter(int filterPos) {
            if (filterPos > Filter.values().length)
                return ALL;
            else 
                return Filter.values()[filterPos];
        }
    }

    private OnItemSelectedListener mFilterSelectedListener = new OnItemSelectedListener() {
;

        @Override
        public void onItemSelected(IcsAdapterView<?> parent, View view, int position, long id) {
            mCursor = null;
            setFilter(Filter.getFilter(position));
        }

        @Override
        public void onNothingSelected(IcsAdapterView<?> parent) { }
    };
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        
        View view = inflater.inflate(R.layout.media_grid_fragment, container);
        
        mGridView = (MultiSelectGridView) view.findViewById(R.id.media_gridview);
        mGridView.setOnItemClickListener(this);
        mGridView.setRecyclerListener(this);
        mGridView.setMultiSelectListener(this);

        mSpinnerContainer = view.findViewById(R.id.media_filter_spinner_container);
        mSpinnerContainer.setOnClickListener(new OnClickListener() {
            
            @Override
            public void onClick(View v) {
                if (mSpinner != null && !isInMultiSelect()) {
                    mSpinner.performClick();
                }
            }

        });

        mFiltersText = new String[Filter.values().length];
        mSpinner = (IcsSpinner) view.findViewById(R.id.media_filter_spinner);
        mSpinner.setOnItemSelectedListener(mFilterSelectedListener);
        setupSpinnerAdapter();
        
        mCheckedItems = new ArrayList<String>();
        restoreState(savedInstanceState);
        
        return view;
    }

    private void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState == null)
            return;
        if (savedInstanceState.containsKey(BUNDLE_CHECKED_STATES)) {
            mCheckedItems = savedInstanceState.getStringArrayList(BUNDLE_CHECKED_STATES);
            mListener.onMultiSelectChange(mCheckedItems.size());
        }
        
    }
    
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        saveState(outState);
    }

    private void saveState(Bundle outState) {
        outState.putStringArrayList(BUNDLE_CHECKED_STATES, mCheckedItems);
    }

    private void setupSpinnerAdapter() {
        if (getActivity() == null || WordPress.getCurrentBlog() == null)
            return; 

        updateFilterText();

        Context context = ((WPActionBarActivity) getActivity()).getSupportActionBar().getThemedContext();        
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, R.layout.sherlock_spinner_dropdown_item, mFiltersText);
        mSpinner.setAdapter(adapter);
    }
    
    public void refreshSpinnerAdapter() {
        updateFilterText();
        updateSpinnerAdapter();
    }
    
    private void updateFilterText() {
        if(WordPress.currentBlog == null)
            return;
        
        String blogId = String.valueOf(WordPress.getCurrentBlog().getBlogId());
        
        int countAll = WordPress.wpDB.getMediaCountAll(blogId);
        int countImages = WordPress.wpDB.getMediaCountImages(blogId);
        int countUnattached = WordPress.wpDB.getMediaCountUnattached(blogId);
        
        mFiltersText[0] = getResources().getString(R.string.all) + " (" + countAll + ")";
        mFiltersText[1] = getResources().getString(R.string.images) + " (" + countImages + ")";
        mFiltersText[2] = getResources().getString(R.string.unattached) + " (" + countUnattached + ")";
    }

    private void updateSpinnerAdapter() {
        ArrayAdapter<String> adapter = (ArrayAdapter<String>) mSpinner.getAdapter();
        if (adapter != null) 
            adapter.notifyDataSetChanged();
    }
    
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        
        try {
            mListener = (MediaGridListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement MediaGridListener");
        }
    }
    
    @Override
    public void onResume() {
        super.onResume();
        
        refreshMediaFromDB();
        refreshMediaFromServer(0);
    }

    public void refreshMediaFromDB() {
        setFilter(mFilter);
        if (mCursor != null) {
            mGridAdapter = new MediaGridAdapter(getActivity(), mCursor, 0, mCheckedItems);
            mGridAdapter.setCallback(this);
            mGridView.setAdapter(mGridAdapter);
        }
    }
    
    public void refreshMediaFromServer(int offset) {
        if(WordPress.getCurrentBlog() == null)
            return; 
        
        if(offset == 0 || !mIsRefreshing && (System.currentTimeMillis() - mLastRefreshTime > MIN_REFERSH_INTERVAL_MS)) {
            mLastRefreshTime = System.currentTimeMillis();
            mIsRefreshing = true;
            mListener.onMediaItemListDownloadStart();

            List<Object> apiArgs = new ArrayList<Object>();
            apiArgs.add(WordPress.getCurrentBlog());
            
            ApiHelper.SyncMediaLibraryTask getMediaTask = new ApiHelper.SyncMediaLibraryTask(offset, mFilter, mCallback);
            getMediaTask.execute(apiArgs);
        }
    }

    
    private Callback mCallback = new Callback() {
        
        @Override
        public void onSuccess() {
            mIsRefreshing = false;
            
            if (MediaGridFragment.this.isVisible()) {
                Toast.makeText(getActivity(), "Refreshed content", Toast.LENGTH_SHORT).show();
                refreshSpinnerAdapter();
                setFilter(mFilter);
            }

            mListener.onMediaItemListDownloaded();
        }

        @Override
        public void onFailure() {
            mIsRefreshing = false;
            
            if (MediaGridFragment.this.isVisible()) {
                Toast.makeText(getActivity(), "Failed to refresh content", Toast.LENGTH_SHORT).show();
            }
            mListener.onMediaItemListDownloaded();
        }
    };

    public void search(String searchTerm) {
        Blog blog = WordPress.getCurrentBlog();
        if(blog != null) {
            String blogId = String.valueOf(blog.getBlogId());
            mCursor = WordPress.wpDB.getMediaFilesForBlog(blogId, searchTerm);
            mGridAdapter.changeCursor(mCursor);
        }
    }
    
    public boolean isRefreshing() {
        return mIsRefreshing;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Cursor cursor = ((MediaGridAdapter) parent.getAdapter()).getCursor();
        String mediaId = cursor.getString(cursor.getColumnIndex("mediaId"));
        mListener.onMediaItemSelected(mediaId);
    }

    public void setFilterVisibility(int visibility) {
        if (mSpinner != null)
            mSpinner.setVisibility(visibility);
    }
    
    public void setFilter(Filter filter) {
        mFilter = filter;
        mSpinner.setSelection(mFilter.ordinal());
        mCursor = filterItems(mFilter);
        
        if (mCursor != null && mGridAdapter != null) {
            mGridAdapter.swapCursor(mCursor);
        }
    }

    private Cursor filterItems(Filter filter) {
        Blog blog = WordPress.getCurrentBlog();
        
        if(blog == null)
            return null;
        
        String blogId = String.valueOf(blog.getBlogId());
        
        switch (filter) {
        case ALL:
            return WordPress.wpDB.getMediaFilesForBlog(blogId);
        case IMAGES:
            return WordPress.wpDB.getMediaImagesForBlog(blogId);
        case UNATTACHED:
            return WordPress.wpDB.getMediaUnattachedForBlog(blogId);
        }
        return null;
    }

    @Override
    public void onPrefetchData(int offset) {
        refreshMediaFromServer(offset);
    }

    @Override
    public void onMovedToScrapHeap(View view) {
        
        // cancel image fetch requests if the view has been moved to recycler.
        
        NetworkImageView niv = (NetworkImageView) view.findViewById(R.id.media_grid_item_image);
        if (niv != null) {
            // this tag is set in the MediaGridAdapter class
            String tag = (String) niv.getTag();
            if (tag != null) {
                // need a listener to cancel request, even if the listener does nothing
                ImageContainer container = WordPress.imageLoader.get(tag, new ImageListener() {

                    @Override
                    public void onErrorResponse(VolleyError error) { }

                    @Override
                    public void onResponse(ImageContainer response, boolean isImmediate) { }
                    
                });
                container.cancelRequest();
            }
        }
        
        CheckableFrameLayout layout = (CheckableFrameLayout) view;
        if (layout != null) {
            layout.setOnCheckedChangeListener(null);
        }
                
    }

    @Override
    public void onMultiSelectChange(int count) {
        if (count == 0) {
            // enable filtering when not in multiselect
            mSpinner.setEnabled(true);
            mSpinnerContainer.setEnabled(true);
            mSpinnerContainer.setVisibility(View.VISIBLE);
        } else {
            // disable filtering on multiselect
            mSpinner.setEnabled(false);
            mSpinnerContainer.setEnabled(false);
            mSpinnerContainer.setVisibility(View.GONE);
        }
        
        mListener.onMultiSelectChange(count);
    }
    
    private boolean isInMultiSelect() {
        return mCheckedItems.size() > 0;
    }
    
    public ArrayList<String> getCheckedItems() {
        return mCheckedItems;
    }
    
    public void clearCheckedItems() {
        mGridView.cancelSelection();
    }

}
