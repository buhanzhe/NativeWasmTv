"""Check request isolation, frozen first-frame timing and late worker completion."""
from pathlib import Path
import os, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
jdk = Path(os.environ.get('JAVA_HOME', 'C:/Users/Administrator/.jdks/corretto-1.8.0_492')) / 'bin'
test = '''package xiao.bu.tv;
class StartupTimingCheck {
static void check(boolean ok) { if (!ok) throw new AssertionError(); }
public static void main(String[] args) {
ChannelStartupTiming t = new ChannelStartupTiming();
t.begin(1, 100); t.stage(1, "resolve-begin", 120);
t.stage(1, "url-resolved", 140); t.stage(1, "player-open-requested", 150);
t.stage(1, "player-prepared", 200); t.stage(1, "segments-ready", 180);
t.segment(1, 30, 0, false); t.stage(1, "release-begin", 160);
check(!t.finished(1)); t.stage(1, "load-ready", 250); check(t.finished(1));
String display=t.display(1), log=t.format(1);
check(display.contains("解析:20ms")); check(display.contains("准备:50ms"));
check(display.contains("出帧:50ms")); check(display.contains("加载:70ms"));
check(display.contains("总计:150ms")); check(!display.contains("解密"));
check(log.endsWith(display.substring(display.indexOf(" 准备:"))));
t.stage(1, "load-ready", 900); t.segment(1, 500, 500, true);
check(display.equals(t.display(1))); t.stage(1, "release-end", 400);
check(t.format(1).contains("释放:240ms"));
t.begin(2, 1000); check(!t.finished(2)); check(!t.finished(1));
t.stage(1, "load-ready", 1200); check(!t.finished(2));
t.stage(2, "first-data", 1100); t.stage(2, "load-ready", 1200);
t.cost(2, "下载", 300); t.cost(2, "下载", 500);
check(t.display(2).contains("下载:300ms"));
check(t.display(2).contains("加载:边下边播"));
t.begin(3, 2000); t.segment(3, 20, 10, true);
t.stage(3, "player-open-requested", 2050); t.stage(3, "player-prepared", 2100); t.stage(3, "load-ready", 2200);
check(t.display(3).contains("解密:10ms")); check(t.display(3).contains("准备:50ms"));
t.begin(4, 3000); t.stage(4, "first-data", 3010);
t.progressiveDecrypt(3, 999); t.progressiveDecrypt(4, -1);
check(!t.display(4).contains("解密"));
t.progressiveDecrypt(4, 0); check(t.display(4).contains("解密:0ms"));
t.progressiveDecrypt(4, 7); t.segment(4, 20, 7, true);
check(t.display(4).contains("解密:7ms"));
t.progressiveDecrypt(4, 3); t.stage(4, "load-ready", 3100);
check(t.display(4).contains("解密:10ms"));check(t.format(4).contains("解密:10ms"));
String frozen=t.display(4);t.progressiveDecrypt(4, 900);t.segment(4, 800, 910, true);
check(frozen.equals(t.display(4)));check(frozen.contains("总计:100ms"));
t.begin(5, 4000);t.segment(5, 15, 6, true);
check(t.display(5).contains("解密:6ms"));
t.begin(6, 5000);t.stage(6,"resolve-begin",5000);t.stage(6,"release-begin",5000);
t.stage(6,"url-selected",5100);t.stage(6,"url-selected",5200);
t.stage(6,"release-end",5500);t.stage(6,"url-resolved",5600);
check(t.display(6).contains("解析:100ms"));check(t.format(6).contains("待应用:500ms"));
check(t.format(6).contains("释放:500ms"));t.stage(6,"load-ready",5900);
check(t.display(6).contains("总计:900ms"));
t.begin(7,6000);t.stage(7,"player-open-requested",6050);
t.stage(6,"player-demux-opened",6060); // stale player's native events cannot contaminate this probe
check(!t.format(7).contains(" 输入:"));
t.stage(7,"player-demux-opened",6250);t.stage(7,"player-streams-found",6330);
t.stage(7,"player-components-opened",6400);t.stage(7,"player-prepared",6420);
check(t.format(7).contains("输入:200ms 流探测:80ms 组件:70ms 回调:20ms"));
check(t.display(7).contains("准备:370ms"));
t.stage(7,"load-ready",6500);String detailed=t.format(7);
t.stage(7,"player-streams-found",6800);check(detailed.equals(t.format(7)));
t.begin(8,7000);check(!t.format(8).contains(" 输入:"));
}
}
'''
with tempfile.TemporaryDirectory(prefix='ntv-startup-') as folder:
    out = Path(folder)
    source = out / 'StartupTimingCheck.java'
    source.write_text(test, encoding='utf8')
    subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '-d', folder,
        str(root/'app/src/main/java/xiao/bu/tv/ChannelStartupTiming.java'), str(source)], check=True)
    subprocess.run([str(jdk/'java.exe'), '-cp', folder, 'xiao.bu.tv.StartupTimingCheck'], check=True)
print('PASS request isolation, first-frame freeze, worker costs and shared timing text')
