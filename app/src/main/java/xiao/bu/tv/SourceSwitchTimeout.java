package xiao.bu.tv;

final class SourceSwitchTimeout {
    static boolean valid(int seconds) { return seconds >= 0 && seconds <= 30 && seconds % 5 == 0; }
}
