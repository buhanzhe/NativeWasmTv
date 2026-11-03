package xiao.bu.tv;

import android.view.View;
import android.widget.ListView;

/** UI-thread, allocation-free DPAD steps. Touch keeps the ordinary ListView scroll path. */
final class RemoteListPager {
    private ListView list;
    private int left, top, right, bottom, start, selected = -1, pitch, rowHeight, capacity;
    private boolean clip, active;
    private boolean anchorPending;private int anchorAttempts;
    private final android.view.ViewTreeObserver.OnPreDrawListener anchor = new android.view.ViewTreeObserver.OnPreDrawListener(){
        public boolean onPreDraw(){
            if(list==null||!active)return true;
            View row=list.getSelectedView();int wanted=top+(selected-start)*pitch;
            if((list.getFirstVisiblePosition()==start&&row!=null&&Math.abs(row.getTop()-wanted)<=1)||anchorAttempts++>=3){
                if(list.getViewTreeObserver().isAlive())list.getViewTreeObserver().removeOnPreDrawListener(this);anchorPending=false;return true;
            }
            // Adapter notifications retain a sync anchor from the preceding page.
            // Correct once after layout consumes it, before exposing that frame.
            list.setSelectionFromTop(selected,(selected-start)*pitch);list.requestLayout();return false;
        }
    };

    void reset() {
        if(list!=null&&anchorPending&&list.getViewTreeObserver().isAlive())list.getViewTreeObserver().removeOnPreDrawListener(anchor);
        anchorPending=false;
        if (list != null && active) {
            list.setPadding(left, top, right, bottom);
            list.setClipToPadding(clip);
        }
        list = null; active = false; selected = -1;
    }

    int position(int fallback) { return active && selected >= 0 ? selected : fallback; }
    int first(int fallback) { return active ? start : fallback; }

    private void prepare(ListView view, int count, int current, int height) {
        if (list != view) reset();
        boolean beginning=!active;
        if (!active) {
            list = view; left = view.getPaddingLeft(); top = view.getPaddingTop();
            right = view.getPaddingRight(); bottom = view.getPaddingBottom();
            // All three XML lists use clipToPadding=false on older Android.
            clip = android.os.Build.VERSION.SDK_INT >= 21 && Api21.clipToPadding(view);
            start = view.getFirstVisiblePosition();
            View first = view.getChildAt(0);
            if (first != null && first.getTop() < top-2 && current != start) start++;
            selected = Math.max(0, Math.min(count - 1, current)); active = true;
        }
        rowHeight = Math.max(1, height); pitch = rowHeight + view.getDividerHeight();
        selected=Math.max(0,Math.min(count-1,selected));start=Math.max(0,Math.min(count-1,start));
        capacity = Math.max(1, (view.getHeight() - top - bottom + view.getDividerHeight()) / pitch);
        if(beginning&&(selected<start||selected>=start+capacity))selected=start;
        if(beginning)start=selected/capacity*capacity;
        if (selected < start || selected >= start + capacity) start = selected / capacity * capacity;
    }

    int move(ListView view, int count, int current, int offset, int height) {
        return move(view, count, current, offset, height, false);
    }

    int moveWrapping(ListView view, int count, int current, int offset, int height) {
        return move(view, count, current, offset, height, true);
    }

    private int move(ListView view, int count, int current, int offset, int height, boolean wrap) {
        if (count <= 0) { reset(); return -1; }
        prepare(view, count, current, height);
        long target = (long) selected + offset;
        int next = wrap ? (int) ((target % count + count) % count)
                : (int) Math.max(0L, Math.min(count - 1L, target));
        // Wrap onto a complete navigation page, including a short final page.
        if (wrap && (target < 0 || target >= count)) start = next / capacity * capacity;
        else if (next >= start + capacity) start = next;
        else if (next < start) start = Math.max(0, start - capacity);
        selected = next; apply(count); return next;
    }

    void show(ListView view, int count, int position, int first, int height) {
        if (count <= 0) { reset(); return; }
        prepare(view, count, position, height);
        selected = Math.max(0, Math.min(count - 1, position));
        start = Math.max(0, Math.min(count - 1, first))/capacity*capacity;
        if (selected < start || selected >= start + capacity) start = selected / capacity * capacity;
        apply(count);
    }

    void reapply(int count) { if (active && count > 0) apply(count); }

    private void apply(int count) {
        int visible = Math.min(capacity, count - start);
        int used = Math.max(1, visible * pitch - list.getDividerHeight());
        // Padding, rather than a selectable footer, lets even a short final page
        // begin at the top and keeps incomplete rows outside the navigation page.
        int tail = Math.max(bottom, list.getHeight() - top - used);
        if (list.getPaddingBottom() != tail) list.setPadding(left, top, right, tail);
        list.setClipToPadding(true);
        list.setItemChecked(selected, true);
        list.setSelectionFromTop(selected, (selected - start) * pitch);
        anchorAttempts=0;
        if(!anchorPending){anchorPending=true;list.getViewTreeObserver().addOnPreDrawListener(anchor);}
    }
    @android.annotation.TargetApi(21)
    private static final class Api21 { static boolean clipToPadding(ListView list){return list.getClipToPadding();} }
}
