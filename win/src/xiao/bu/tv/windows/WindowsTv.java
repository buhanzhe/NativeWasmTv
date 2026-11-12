package xiao.bu.tv.windows;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import javax.swing.*;

/** Minimal native desktop shell: playlist switch, channel switch and playback. */
public final class WindowsTv {
  private final JFrame frame = new JFrame("网页电视 · Windows");
  private final DefaultComboBoxModel<Playlist> sources = new DefaultComboBoxModel<>();
  private final JComboBox<Playlist> playlists = new JComboBox<>(sources);
  private final DefaultListModel<Playlist.Channel> channels = new DefaultListModel<>();
  private final JList<Playlist.Channel> list = new JList<>(channels);
  private final JLabel status = new JLabel("打开本地 M3U/TXT，或添加一个播放列表地址");
  private final PlayerController controller;
  private boolean selecting;
  private long loadGeneration;

  private WindowsTv(Path home) {
    Path profile = Path.of(System.getProperty("user.home"),".ntv-windows");
    String executable = System.getProperty("os.name").startsWith("Windows") ? "ffplay.exe" : "ffplay";
    controller = new PlayerController(home.resolve("player").resolve(executable),profile,
        text -> SwingUtilities.invokeLater(() -> status.setText(text)));
    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    frame.addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent e) { controller.close(); } });
    JPanel toolbar = new JPanel(new BorderLayout(8,0));
    toolbar.add(playlists,BorderLayout.CENTER);
    JPanel imports = new JPanel(new FlowLayout(FlowLayout.RIGHT,4,0));
    JButton local = new JButton("打开列表"), remote = new JButton("添加地址");
    imports.add(local); imports.add(remote); toolbar.add(imports,BorderLayout.EAST);
    local.addActionListener(e -> {
      JFileChooser picker = new JFileChooser();
      picker.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("播放列表 (*.m3u, *.m3u8, *.txt)","m3u","m3u8","txt"));
      if (picker.showOpenDialog(frame)==JFileChooser.APPROVE_OPTION) load(picker.getSelectedFile().getAbsolutePath());
    });
    remote.addActionListener(e -> {
      String address=JOptionPane.showInputDialog(frame,"播放列表 HTTP(S) 地址：");
      if (address!=null && !address.isBlank()) load(address.trim());
    });
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.addListSelectionListener(e -> { if (!selecting && !e.getValueIsAdjusting()) play(); });
    list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"play");
    list.getActionMap().put("play",new AbstractAction() { public void actionPerformed(ActionEvent e) { play(); } });
    playlists.addActionListener(e -> switchPlaylist());
    JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
    JButton previous=new JButton("上一台"), play=new JButton("播放"), next=new JButton("下一台"), stop=new JButton("停止");
    controls.add(previous); controls.add(play); controls.add(next); controls.add(stop);
    previous.addActionListener(e -> move(-1)); next.addActionListener(e -> move(1));
    play.addActionListener(e -> play()); stop.addActionListener(e -> { controller.stop(); status.setText("已停止"); });
    JPanel bottom = new JPanel(new BorderLayout()); bottom.add(controls,BorderLayout.NORTH); bottom.add(status,BorderLayout.SOUTH);
    JPanel content = new JPanel(new BorderLayout(8,8)); content.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
    content.add(toolbar,BorderLayout.NORTH); content.add(new JScrollPane(list),BorderLayout.CENTER); content.add(bottom,BorderLayout.SOUTH);
    frame.setContentPane(content); frame.setSize(720,560); frame.setMinimumSize(new Dimension(520,360)); frame.setLocationRelativeTo(null);
  }

  private void load(String source) {
    long token=++loadGeneration; status.setText("正在读取播放列表…");
    new SwingWorker<Playlist,Void>() {
      protected Playlist doInBackground() throws Exception { return Playlist.load(source); }
      protected void done() {
        if (token!=loadGeneration || !frame.isDisplayable()) return;
        try {
          Playlist loaded=get(); sources.addElement(loaded); playlists.setSelectedItem(loaded);
          status.setText("已加载 " + loaded.channels.size() + " 个频道" + (loaded.rejected>0 ? "，跳过 " + loaded.rejected + " 个无效条目" : "") + "；选择频道即可播放");
        } catch (Exception e) { status.setText("加载失败：" + (e.getCause()==null ? e.getMessage() : e.getCause().getMessage())); }
      }
    }.execute();
  }

  private void switchPlaylist() {
    controller.stop(); selecting=true;
    try {
      channels.clear(); Playlist p=(Playlist)playlists.getSelectedItem();
      if (p!=null) for (Playlist.Channel channel:p.channels) channels.addElement(channel);
    } finally { selecting=false; }
  }
  private void play() { Playlist.Channel c=list.getSelectedValue(); if (c!=null) controller.play(c); }
  private void move(int delta) {
    if (channels.isEmpty()) return;
    int selected=list.getSelectedIndex();
    list.setSelectedIndex(selected<0 ? 0 : Math.floorMod(selected+delta,channels.size()));
    list.ensureIndexIsVisible(list.getSelectedIndex());
  }

  public static void main(String[] args) throws Exception {
    xiao.bu.tv.WindowsSiteConfiguration.install();
    Path home=Path.of(System.getProperty("ntv.home",".")).toAbsolutePath();
    String suffix=System.getProperty("os.name").startsWith("Windows") ? "ntvLiteDesktop.dll" : "libntvLiteDesktop.so";
    System.setProperty("ntv.library",home.resolve("app").resolve(suffix).toString());
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    SwingUtilities.invokeLater(() -> {
      WindowsTv app=new WindowsTv(home); app.frame.setVisible(true);
      String source=args.length>0 ? args[0] : home.resolve("playlists/webview.txt").toString();
      if (source.startsWith("https://") || source.startsWith("http://") || Files.isRegularFile(Path.of(source))) app.load(source);
    });
  }
}
