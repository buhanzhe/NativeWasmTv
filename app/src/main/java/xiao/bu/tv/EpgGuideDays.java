package xiao.bu.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/** Dates come from usable EPG entries, never a synthetic seven-day range. */
final class EpgGuideDays {
    static List<Long> available(List<EpgManager.Program> programs,double catchupDays,long now) {
        if(programs==null||programs.isEmpty())return Collections.emptyList();
        long today=EpgManager.dayStart(now);
        long earliest=EpgManager.dayStart(today-(long)(Math.max(0,catchupDays)*86400000L));
        TreeSet<Long> dates=new TreeSet<Long>();
        for(EpgManager.Program p:programs){
            if(p.stopMillis<=p.startMillis||p.stopMillis<=earliest)continue;
            dates.add(Math.max(earliest,EpgManager.dayStart(p.startMillis)));
        }
        return Collections.unmodifiableList(new ArrayList<Long>(dates));
    }
    private EpgGuideDays(){}
}
