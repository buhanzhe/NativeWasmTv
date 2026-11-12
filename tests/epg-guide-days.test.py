"""Use actual guide/date-cache code for one-day, future, catchup and DST behavior."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
java='''package xiao.bu.tv;import java.util.*;
class EpgManager{static class Program{final long startMillis,stopMillis;final String title;Program(long a,long b,String t){startMillis=a;stopMillis=b;title=t;}}static long dayStart(long now){Calendar c=Calendar.getInstance();c.setTimeInMillis(now);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}}
class Check{static void ok(boolean b){if(!b)throw new AssertionError();}static EpgManager.Program p(long a,long b){return new EpgManager.Program(a,b,"test");}
 public static void main(String[]args){for(String zone:new String[]{"Asia/Shanghai","America/New_York"}){
 TimeZone.setDefault(TimeZone.getTimeZone(zone));Calendar c=Calendar.getInstance();c.set(2026,Calendar.MARCH,8,12,0,0);long now=c.getTimeInMillis(),today=EpgManager.dayStart(now);c.setTimeInMillis(today);c.add(Calendar.DATE,1);long tomorrow=c.getTimeInMillis();c.setTimeInMillis(today);c.add(Calendar.DATE,-1);long yesterday=c.getTimeInMillis();
 List<EpgManager.Program> one=Arrays.asList(p(today+3600000,today+7200000));ok(EpgGuideDays.available(one,0,now).equals(Arrays.asList(today)));
 List<EpgManager.Program> both=Arrays.asList(p(tomorrow+3600000,tomorrow+7200000),one.get(0),one.get(0));ok(EpgGuideDays.available(both,0,now).equals(Arrays.asList(today,tomorrow)));
 ok(EpgGuideDays.available(Arrays.asList(p(yesterday+3600000,yesterday+7200000),one.get(0)),0,now).equals(Arrays.asList(today)));
 ok(EpgGuideDays.available(Arrays.asList(p(yesterday+3600000,yesterday+7200000),one.get(0)),2,now).equals(Arrays.asList(yesterday,today)));
 ok(EpgGuideDays.available(Arrays.asList(p(today-60000,today+60000)),0,now).equals(Arrays.asList(today)));
 ok(EpgGuideDays.available(Arrays.asList(p(today+5,today+1)),0,now).isEmpty());ok(EpgGuideDays.available(Collections.<EpgManager.Program>emptyList(),0,now).isEmpty());
 List<EpgManager.Program> week=new ArrayList<EpgManager.Program>();List<Long> expected=new ArrayList<Long>();
 for(int i=6;i>=0;i--){Calendar date=Calendar.getInstance();date.setTimeInMillis(today);date.add(Calendar.DATE,-i);long day=date.getTimeInMillis();expected.add(day);for(int hour=0;hour<24;hour++){Calendar begin=(Calendar)date.clone();begin.set(Calendar.HOUR_OF_DAY,hour);Calendar end=(Calendar)begin.clone();end.add(Calendar.HOUR_OF_DAY,1);week.add(p(begin.getTimeInMillis(),end.getTimeInMillis()));}}
 Calendar expired=Calendar.getInstance();expired.setTimeInMillis(today);expired.add(Calendar.DATE,-9);week.add(p(expired.getTimeInMillis(),expired.getTimeInMillis()+3600000));
 ok(EpgGuideDays.available(week,7,now).equals(expected));
 List<EpgManager.Program> sparse=new ArrayList<EpgManager.Program>();List<Long> sparseExpected=new ArrayList<Long>();
 for(int i=0;i<7;i++){if(i==2||i==4)continue;sparseExpected.add(expected.get(i));sparse.add(p(expected.get(i)+3600000,expected.get(i)+7200000));}
 ok(EpgGuideDays.available(sparse,7,now).equals(sparseExpected));
 EpgDisplayCache daily=new EpgDisplayCache();for(long day:expected){List<EpgManager.Program> rows=daily.programsForDay(week,day,0);ok(!rows.isEmpty());for(EpgManager.Program entry:rows)ok(entry.stopMillis>day);}
 EpgDisplayCache cache=new EpgDisplayCache();ok(cache.programsForDay(both,today,0).size()==1);ok(cache.programsForDay(both,tomorrow,0).size()==1);ok(cache.programsForDay(one,today,0).size()==1);
 }System.out.println("PASS actual-date detection, duplicate entries, future dates, catchup boundary, overnight programme, invalid/empty data and DST-aware date filtering");}}
'''
with tempfile.TemporaryDirectory() as directory:
 p=Path(directory);(p/'Check.java').write_text(java,'utf8');src=root/'app/src/main/java/xiao/bu/tv'
 jdk=Path(os.environ['JAVA_HOME'])/'bin'
 subprocess.run([str(jdk/'javac'),'--release','8','-encoding','UTF-8','-d',directory,str(p/'Check.java'),str(src/'EpgGuideDays.java'),str(src/'EpgDisplayCache.java')],check=True)
 subprocess.run([str(jdk/'java'),'-cp',directory,'xiao.bu.tv.Check'],check=True)
