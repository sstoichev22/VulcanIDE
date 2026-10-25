package VulcanIDE;

import javax.swing.*;
import java.awt.Component;
import java.util.*;
import java.util.function.*;

/** Visibility state of the optional workspace panes. */
final class WorkspaceViews {
    private record View(BooleanSupplier visible, Consumer<Boolean> change) {}
    private final Map<String, View> views = new LinkedHashMap<>();
    private final Map<JSplitPane, SplitGroup> splits = new IdentityHashMap<>();

    void add(String name, BooleanSupplier visible, Consumer<Boolean> change) {
        views.put(name, new View(visible, change));
    }

    void addSplit(String name, JSplitPane split, boolean first) {
        SplitGroup group = group(split);
        add(name, () -> group.visible(first), value -> group.set(first, value));
    }

    void addConsole(String name, JSplitPane split, boolean first, ConsolePanel console) {
        SplitGroup group = group(split);
        add(name, () -> group.visible(first), value -> { console.clear(); group.set(first, value); });
    }

    SplitGroup group(JSplitPane split) { return splits.computeIfAbsent(split, SplitGroup::new); }

    Map<String, Boolean> options() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        views.forEach((name, view) -> result.put(name, view.visible.getAsBoolean()));
        return result;
    }

    void set(String name, boolean visible) {
        View view = views.get(name);
        if (view != null && view.visible.getAsBoolean() != visible) view.change.accept(visible);
    }

    /** Split pane whose children can be shown or hidden individually. */
    static final class SplitGroup {
        private final JSplitPane split;
        private final Component first;
        private final Component second;
        private boolean firstVisible = true;
        private boolean secondVisible = true;
        private int divider;

        SplitGroup(JSplitPane split) {
            this.split = split; first = split.getLeftComponent(); second = split.getRightComponent();
            divider = split.getDividerLocation();
            split.addComponentListener(new java.awt.event.ComponentAdapter(){
                @Override public void componentResized(java.awt.event.ComponentEvent event){fitVerticalDivider();}
            });
        }

        private void fitVerticalDivider(){
            if(firstVisible&&secondVisible&&split.getOrientation()==JSplitPane.VERTICAL_SPLIT&&split.getHeight()>0){
                int maximum=Math.max(0,split.getHeight()-84);
                if(split.getDividerLocation()>maximum)split.setDividerLocation(maximum);
            }
        }

        boolean visible(boolean firstSide) { return firstSide ? firstVisible : secondVisible; }
        int savedDivider() { return firstVisible && secondVisible ? split.getDividerLocation() : divider; }

        void set(boolean firstSide, boolean visible) {
            if (visible(firstSide) == visible) return;
            if (firstVisible && secondVisible) divider = split.getDividerLocation();
            if (firstSide) firstVisible = visible; else secondVisible = visible;
            split.setLeftComponent(firstVisible ? first : null);
            split.setRightComponent(secondVisible ? second : null);
            boolean divided = firstVisible && secondVisible;
            split.putClientProperty("vulcan.hiddenDivider", !divided);
            split.setDividerSize(divided ? 4 : 0);
            split.setDividerLocation(divided ? divider : firstVisible ? Integer.MAX_VALUE : 0);
            fitVerticalDivider();split.revalidate(); split.repaint();
        }
    }
}
