package xiao.bu.tv.windows;

import java.awt.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class UiSmoke {
  static <T> T edt(Callable<T> work) throws Exception {
    FutureTask<T> task=new FutureTask<>(work); SwingUtilities.invokeAndWait(task); return task.get();
  }
  static <T extends Component> T find(Component c,Class<T> type,Predicate<T> match) {
    if (type.isInstance(c) && match.test(type.cast(c))) return type.cast(c);
    if (c instanceof Container) for (Component child:((Container)c).getComponents()) {
      T result=find(child,type,match); if (result!=null) return result;
    }
    return null;
  }
  static void waitFor(Callable<Boolean> test,String label,int seconds) throws Exception {
    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime()<until) { if (test.call()) return; Thread.sleep(100); }
    throw new AssertionError(label);
  }
  @SuppressWarnings({"rawtypes","unchecked"})
  public static void main(String[] args) throws Exception {
    System.setProperty("ntv.home",args[0]);
    WindowsTv.main(new String[]{args[1]});
    JFrame frame=null;
    try {
      waitFor(() -> edt(() -> { for (Frame f:Frame.getFrames()) if (f instanceof JFrame && f.isShowing()) return true; return false; }),"No actual window",10);
      frame=edt(() -> { for (Frame f:Frame.getFrames()) if (f instanceof JFrame && f.isShowing()) return (JFrame)f; throw new AssertionError(); });
      JFrame shown=frame;
      JList list=edt(() -> find(shown,JList.class,c -> true));
      JComboBox combo=edt(() -> find(shown,JComboBox.class,c -> true));
      waitFor(() -> edt(() -> list.getModel().getSize()==2),"Initial playlist did not load",10);
      Playlist second=Playlist.load(args[2]);
      edt(() -> { ((DefaultComboBoxModel)combo.getModel()).addElement(second); combo.setSelectedItem(second); return null; });
      waitFor(() -> edt(() -> list.getModel().getSize()==1 && list.getModel().getElementAt(0).toString().contains("Second")),"Playlist switching failed",3);
      edt(() -> { combo.setSelectedIndex(0); list.setSelectedIndex(0); return null; });
      Robot robot=new Robot();
      Path screen=Path.of(args[3]);
      waitFor(() -> {
        java.awt.image.BufferedImage image=robot.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
        int green=0;
        for (int y=0;y<image.getHeight();y+=4) for (int x=0;x<image.getWidth();x+=4) {
          int pixel=image.getRGB(x,y),r=(pixel>>16)&255,g=(pixel>>8)&255,b=pixel&255;
          if (g>100 && r<35 && b<35) green++;
        }
        if (green>5000) { ImageIO.write(image,"png",screen.toFile()); return true; }
        return false;
      },"Actual FFplay video frame not visible",35);
      // Windows may keep its console host attached to this JVM. Check the
      // actual decoder PID, rather than treating conhost.exe as a leaked player.
      java.util.List<ProcessHandle> decoders=ProcessHandle.current().children()
          .filter(p -> p.info().command().orElse("").toLowerCase(java.util.Locale.ROOT).contains("ffplay")).toList();
      if (decoders.isEmpty()) throw new AssertionError("Visible video had no owned FFplay decoder");
      JButton stop=edt(() -> find(shown,JButton.class,c -> c.getText().equals("停止")));
      edt(() -> { stop.doClick(); return null; });
      waitFor(() -> decoders.stream().noneMatch(ProcessHandle::isAlive),"Stop leaked decoder process",10);
      edt(() -> { shown.dispose(); return null; });
      System.out.println("PASS minimal window, playlist switching, original-page MSE, actual FFplay green frame and stop cleanup");
    } finally {
      JFrame close=frame;
      if (close!=null) edt(() -> { close.dispose(); return null; });
    }
  }
}
