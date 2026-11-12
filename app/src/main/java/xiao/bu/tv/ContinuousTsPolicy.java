package xiao.bu.tv;

final class ContinuousTsPolicy {
    private ContinuousTsPolicy() {}
    static boolean defaultEnabled(int physicalCores) { return physicalCores == 1; }
    static int physicalCores() {
        try {
            java.io.File[] entries=new java.io.File("/sys/devices/system/cpu").listFiles();int count=0;
            if(entries!=null)for(java.io.File entry:entries)if(entry.getName().matches("cpu[0-9]+"))count++;
            if(count>0)return count;
        } catch(SecurityException ignored) {}
        return Runtime.getRuntime().availableProcessors();
    }
}
