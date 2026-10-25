package VulcanIDE;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.*;

/** Toolbar button listing running programs and their build status. */
final class RunningPrograms extends JPanel {
 private final LinkedHashMap<Path, EditorPanel> programs = new LinkedHashMap<>();
 private final JButton list = new JButton("Running programs ▾");
 private final JLabel buildLabel = new JLabel();
 private final JProgressBar progress = new JProgressBar();
 RunningPrograms(EditorTab owner) {
  super(new FlowLayout(FlowLayout.RIGHT, 7, 3));
  progress.setIndeterminate(true);progress.setPreferredSize(new Dimension(80,5));
  add(buildLabel);add(progress);add(list);buildLabel.setVisible(false);progress.setVisible(false);list.setVisible(false);setVisible(false);
  list.addActionListener(e->showPrograms());
  javax.swing.Timer state=new javax.swing.Timer(120,e->refreshState());
  addHierarchyListener(e->{if(isDisplayable())state.start();else state.stop();});
 }
 private final Set<EditorPanel> removedPanels=Collections.newSetFromMap(new WeakHashMap<>());
 private final Map<EditorPanel,String> builds=new IdentityHashMap<>();
 void setBuildStatus(EditorPanel panel,String text){Runnable update=()->{if(panel!=null&&removedPanels.contains(panel))return;if(text==null||text.isBlank())builds.remove(panel);else builds.put(panel,text);showBuildStatus();};if(SwingUtilities.isEventDispatchThread())update.run();else SwingUtilities.invokeLater(update);}
 void setBuildStatus(String text){setBuildStatus(null,text);}
 private void showBuildStatus(){String text=builds.values().stream().findFirst().orElse("");boolean busy=!text.isBlank();buildLabel.setText(text);buildLabel.setVisible(busy);progress.setVisible(busy);refreshState();}
 void selected(Path path){refreshState();}
 void started(EditorPanel panel){Path path=panel.getCurrentPath();if(path!=null){programs.entrySet().removeIf(entry->entry.getValue()==panel&&!entry.getKey().equals(path));programs.put(path,panel);}refreshState();}
 void removed(EditorPanel panel){removedPanels.add(panel);programs.values().removeIf(value->value==panel);builds.remove(panel);showBuildStatus();}
 void clear(){removedPanels.addAll(programs.values());programs.clear();builds.clear();showBuildStatus();}
 private void refreshState(){
  boolean running=programs.values().stream().anyMatch(EditorPanel::isProgramExecuting);
  boolean visible=running||progress.isVisible();
  if(list.isVisible()!=running||isVisible()!=visible){list.setVisible(running);setVisible(visible);revalidate();repaint();}
 }
 private void showPrograms(){JPopupMenu menu=programsMenu();menu.show(list,0,list.getHeight());}
 JPopupMenu programsMenu(){JPopupMenu menu=new JPopupMenu();for(var e:programs.entrySet()){EditorPanel panel=e.getValue();if(!panel.isProgramExecuting())continue;JPanel row=new JPanel(new FlowLayout(FlowLayout.LEFT));JLabel source=new JLabel(e.getKey().getFileName().toString()+" |");source.setToolTipText(e.getKey().toString());
 row.add(source);JButton rerun=new JButton(Icons.vector("run",15,new Color(47,184,92))),stop=new JButton(Icons.vector("stop",15,new Color(220,65,75)));rerun.setToolTipText("Rerun");stop.setToolTipText("Stop");rerun.setEnabled(e.getKey().equals(panel.getCurrentPath())&&panel.canRunCurrentFile());rerun.addActionListener(x->{panel.performRun();menu.setVisible(false);});stop.addActionListener(x->{panel.stopRunning();menu.setVisible(false);refreshState();});row.add(rerun);row.add(stop);menu.add(row);}return menu;}
 static void flash(JButton button) {
  if (!Boolean.TRUE.equals(button.getClientProperty("runFeedbackCaptured"))) {
   button.putClientProperty("runFeedbackOriginal",button.getIcon());
   button.putClientProperty("runFeedbackCaptured",Boolean.TRUE);
  }
  Icon original=(Icon)button.getClientProperty("runFeedbackOriginal");
  Object previous=button.getClientProperty("runFeedbackTimer");
  if(previous instanceof javax.swing.Timer timer)timer.stop();
  button.setIcon(Icons.vector("run",original==null?15:original.getIconWidth(),new Color(35,100,55)));
  javax.swing.Timer timer=new javax.swing.Timer(180,e->{
   button.setIcon((Icon)button.getClientProperty("runFeedbackOriginal"));
   button.putClientProperty("runFeedbackCaptured",null);
   button.putClientProperty("runFeedbackOriginal",null);
   button.putClientProperty("runFeedbackTimer",null);
  });
  button.putClientProperty("runFeedbackTimer",timer);timer.setRepeats(false);timer.start();
 }
}
