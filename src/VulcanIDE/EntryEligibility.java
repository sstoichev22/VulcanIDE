package VulcanIDE;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;

/** Decides whether an assembly or executable file has a usable entry point. */
final class EntryEligibility {
 private static final ExecutorService WORKER=Executors.newFixedThreadPool(2,r->{Thread thread=new Thread(r,"Vulcan-entry-check");thread.setDaemon(true);return thread;});
 private Path path;private String source;private long generation,lastCheck;private boolean ready,runnable;private Future<?> pending;
 private ProgramEligibility.Analysis analysis;
 private List<Path> includeRoots=List.of();
 boolean request(Path nextPath,String nextSource,List<Path> includes,Runnable refresh) {
  boolean changed=!java.util.Objects.equals(path,nextPath)||!java.util.Objects.equals(source,nextSource)||!includeRoots.equals(includes);
  if(changed){generation++;path=nextPath;source=nextSource;includeRoots=List.copyOf(includes);ready=false;analysis=null;if(pending!=null)pending.cancel(true);pending=null;}
  long now=System.nanoTime();
  if(pending==null&&(changed||!ready||now-lastCheck>500_000_000L)) {
   long serial=generation;ProgramEligibility.Analysis cached=analysis;lastCheck=now;
   pending=WORKER.submit(()->{
    ProgramEligibility.Analysis result=cached;
    if(result==null||result.dependencies().entrySet().stream().anyMatch(entry->!ProgramEligibility.stamp(entry.getKey()).equals(entry.getValue())))result=ProgramEligibility.analyze(nextPath,nextSource,includes);
    ProgramEligibility.Analysis published=result;
    SwingUtilities.invokeLater(()->{if(serial!=generation)return;analysis=published;runnable=published.runnable();ready=true;pending=null;refresh.run();});
   });
  }
  return ready&&runnable;
 }
 void stop(){generation++;if(pending!=null)pending.cancel(true);pending=null;ready=false;analysis=null;}
}
