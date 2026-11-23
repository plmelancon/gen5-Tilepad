"""Compile the real PreconditioningService against small Android fixtures and check
bind -> unbind -> return during a session, and the keep-warm behaviour; no car access.

Two clocks, kept apart on purpose:
  SystemClock.elapsed  = SystemClock.elapsedRealtime(): keeps counting while the head unit sleeps.
  SystemClock.uptime   = the handler's scheduling time: stops while the head unit sleeps.
They advance together (Handler.advance) except for Handler.sleep(ms), which moves only the elapsed one.
The service's two threads are told apart by name (tilepad-vehicle / tilepad-battery): every Handler keeps its
Looper's name, Handler.running is the name of the thread whose work is running right now, and drain() runs due
work in creation order (vehicle handler first, then battery handler) until nothing is due, so it is deterministic."""
from pathlib import Path
import subprocess, json, os, shutil
ROOT = Path(__file__).resolve().parents[2]
def java_tool(name):
    executable = name + ('.exe' if os.name == 'nt' else '')
    home = os.environ.get('JAVA_HOME')
    if home:
        candidate = Path(home) / 'bin' / executable
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(executable)
    if found:
        return found
    raise SystemExit('JDK required: set JAVA_HOME or put java and javac on PATH')

JAVAC, JAVA = java_tool('javac'), java_tool('java')
work = ROOT/'build/precon-service-rebind'
if work.exists():
    shutil.rmtree(work)
stubs = {
 'android/os/IBinder': 'public interface IBinder {}',
 'android/os/Binder': 'public class Binder implements IBinder {}',
 'android/os/Looper': 'public class Looper { public String name=""; public boolean quit; }',
 'android/os/SystemClock': '''public class SystemClock { public static long elapsed=1000, uptime=1000;
 public static long elapsedRealtime(){return elapsed;} public static long uptimeMillis(){return uptime;} }''',
 'android/os/HandlerThread': '''public class HandlerThread { final Looper looper=new Looper();
 public HandlerThread(String n){looper.name=n;}
 public void start(){} public Looper getLooper(){return looper;}
 public boolean quitSafely(){looper.quit=true;
  for(Handler h:Handler.all)if(h.looper==looper)h.queue.removeIf(e->(Long)e[1]>SystemClock.uptime); return true;} }''',
 'android/os/Handler': '''import java.util.*;
public class Handler { public static final List<Handler> all=new ArrayList<>(); final List<Object[]> queue=new ArrayList<>();
 public final Looper looper; public static String running="";
 public Handler(Looper l){looper=l;all.add(this);}
 public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long d){if(looper.quit)return false;queue.add(new Object[]{r,SystemClock.uptime+d});return true;}
 public void removeCallbacks(Runnable r){queue.removeIf(e->e[0]==r);}
 public void removeCallbacksAndMessages(Object o){queue.clear();}
 public int pending(){return queue.size();}
 public static Handler named(String n){Handler found=null;for(Handler h:all)if(h.looper.name.equals(n))found=h;return found;}
 public static void reset(){all.clear();running="";}
 public static void drain(){for(boolean ran=true;ran;){ran=false;for(Handler h:new ArrayList<>(all))
  for(Object[] e:new ArrayList<>(h.queue))if((Long)e[1]<=SystemClock.uptime&&h.queue.remove(e)){
   String before=running;running=h.looper.name;try{((Runnable)e[0]).run();}finally{running=before;}ran=true;}}}
 public static void advance(long ms){for(long left=ms;left>0;){long step=Math.min(500,left);left-=step;
  SystemClock.elapsed+=step;SystemClock.uptime+=step;drain();}}
 /** The head unit sleeps: elapsedRealtime keeps counting, handler time (uptime) does not. */
 public static void sleep(long ms){SystemClock.elapsed+=ms;} }''',
 'android/util/Log': '''import java.util.*;
public class Log { public static final List<String> lines=new ArrayList<>();
 public static int i(String tag,String msg){lines.add(tag+": "+msg);return 0;} }''',
 'android/content/SharedPreferences': '''public interface SharedPreferences { boolean getBoolean(String k,boolean d); Editor edit();
 interface Editor { Editor putBoolean(String k,boolean v); boolean commit(); } }''',
 'android/content/Context': '''import java.util.*;
public abstract class Context { public static final int MODE_PRIVATE=0; public final Map<String,Boolean> prefs=new HashMap<>();
 public static final String NOTIFICATION_SERVICE="notification";
 /** The plain notifications the code posted and still shows, by id. */
 public final android.app.NotificationManager notices=new android.app.NotificationManager();
 public Object getSystemService(String n){return NOTIFICATION_SERVICE.equals(n)?notices:null;}
 /** The next this-many commit() calls fail and store nothing, like a full disk. */
 public int failCommits;
 public SharedPreferences getSharedPreferences(String n,int m){return new SharedPreferences(){
  public boolean getBoolean(String k,boolean d){return prefs.getOrDefault(k,d);}
  public Editor edit(){return new Editor(){final Map<String,Boolean> pending=new HashMap<>();
   public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
   public boolean commit(){if(failCommits>0){failCommits--;return false;}prefs.putAll(pending);return true;}};}};} }''',
 'android/content/Intent': '''import java.util.*;
public class Intent { String action; final Map<String,Integer> ints=new HashMap<>(); public Intent(){} public Intent(Context c,Class<?> k){}
 public Intent setAction(String a){action=a;return this;} public String getAction(){return action;}
 public Intent putExtra(String k,int v){ints.put(k,v);return this;} public int getIntExtra(String k,int d){return ints.getOrDefault(k,d);} }''',
 'android/app/PendingIntent': '''import android.content.*;
public class PendingIntent { public static final int FLAG_UPDATE_CURRENT=0;
 public static PendingIntent getActivity(Context c,int r,Intent i,int f){return new PendingIntent();}
 public static PendingIntent getService(Context c,int r,Intent i,int f){return new PendingIntent();} }''',
 'android/app/Notification': '''import android.content.*;
public class Notification { public CharSequence text="";
 public static class BigTextStyle { public BigTextStyle bigText(CharSequence s){return this;} } public static class Builder { CharSequence t=""; public Builder(Context c){}
 public Builder setSmallIcon(int i){return this;} public Builder setContentTitle(CharSequence s){return this;}
 public Builder setContentText(CharSequence s){t=s;return this;} public Builder setOngoing(boolean b){return this;}
 public Builder setContentIntent(PendingIntent p){return this;} public Builder addAction(int i,CharSequence s,PendingIntent p){return this;}
 public Builder setAutoCancel(boolean b){return this;} public Builder setTicker(CharSequence s){return this;}
 public Builder setStyle(BigTextStyle s){return this;}
 public Notification build(){Notification n=new Notification();n.text=t;return n;} } }''',
 'android/app/Service': '''import android.content.*; import android.os.*; import java.util.*;
public abstract class Service extends Context { public static final int START_NOT_STICKY=2; public int stops;
 /** What the platform would show: in the foreground or not, and the notification text. */
 public boolean isForeground; public String foregroundText=""; public final List<String> foregroundTexts=new ArrayList<>();
 public void onCreate(){} public void onDestroy(){} public abstract IBinder onBind(Intent i);
 public boolean onUnbind(Intent i){return false;} public void onRebind(Intent i){}
 public int onStartCommand(Intent i,int f,int s){return START_NOT_STICKY;}
 public final void stopSelf(){stops++;}
 public final void startForeground(int id,Notification n){isForeground=true;foregroundText=String.valueOf(n.text);foregroundTexts.add(foregroundText);}
 public final void stopForeground(boolean b){isForeground=false;} }''',
 'android/app/NotificationManager': '''import java.util.*;
public class NotificationManager { public final Map<Integer,String> shown=new HashMap<>();
 public void notify(int id,Notification n){shown.put(id,String.valueOf(n.text));} public void cancel(int id){shown.remove(id);}
 public String any(){return shown.isEmpty()?"":shown.values().iterator().next();} }''',
 'com/tilepad/R': 'final class R { static final class drawable { static final int ic_notification=1; } }',
 'com/tilepad/MainActivity': 'public class MainActivity {}',
 'com/tilepad/VehicleClientFactory': '''final class VehicleClientFactory { static FakeVehicle current;
 static VehiclePort create(){return current;} }''',
 # The fake car. Every command is logged with the elapsed time it was received at; the Active/Off indicator,
 # temperatures and failures are set by the test.
 'com/tilepad/FakeVehicle': '''import android.os.*; import java.util.*;
final class FakeVehicle implements VehiclePort {
 int temperatureReads, starts, reads, batteryInfoReads, ready=1, indicator=0, enabled=2;
 /** minimum: the Start pre-check's own read. sample: what the battery reader gets (null = the read fails). */
 Integer minimum=15, sample=15; boolean loseReady, stuck;
 long timeoutMs=PreconditioningController.ACTIVATION_TIMEOUT_MS, blockAt, blockMs=30000;
 Runnable infoHook, minimumHook, readHook; long readHookFrom; int readHookNth, readHookCount;
 final List<String> log=new ArrayList<>(), violations=new ArrayList<>(); final List<Long> infoTimes=new ArrayList<>();
 final Map<String,Set<String>> threads=new TreeMap<>(); boolean open;
 void setTemp(Integer c){minimum=c;sample=c;}
 private void on(String what){threads.computeIfAbsent(what,k->new TreeSet<String>()).add(Handler.running);}
 private long now(){return SystemClock.elapsedRealtime();}
 public String unavailableReason(){return "";}
 /** A blocked car service: once, at or after blockAt, this call takes blockMs on both clocks. */
 public Reading read(){if(blockAt!=0&&now()>=blockAt){blockAt=0;SystemClock.elapsed+=blockMs;SystemClock.uptime+=blockMs;}
  on("read");reads++;
  // The Nth read at or after readHookFrom calls back into the test, once (a caller's Stop arriving mid-Start).
  if(readHook!=null&&now()>=readHookFrom&&++readHookCount>=readHookNth){Runnable h=readHook;readHook=null;h.run();}
  return new Reading(enabled,indicator,ready,-1);}
 public Integer readMinimumBatteryTemperatureC(){on("minimum");temperatureReads++;if(loseReady)ready=0;
  if(minimumHook!=null){Runnable h=minimumHook;minimumHook=null;h.run();}return minimum;}
 public BatteryTemperature.Info readBatteryInfo(){on("info");batteryInfoReads++;infoTimes.add(now());
  if(infoHook!=null)infoHook.run();Integer v=sample;return v==null?null:new BatteryTemperature.Info(v,96,113,0);}
 public boolean batteryReaderStuck(){return stuck;}
 public long activationTimeoutMs(){return timeoutMs;}
 public void beginManual(){on("begin");if(open)violations.add("begin while a request is open @"+now());open=true;starts++;log.add("begin@"+now());}
 public void maintainManual(){on("maintain");if(!open)violations.add("maintain with no request @"+now());log.add("maintain@"+now());}
 public void stopAndRelease(){on("stop");open=false;log.add("stop@"+now());}
 public long maintenanceIntervalMs(){return 5000;} }''',
}
for name, body in stubs.items():
    path = work/'src'/f'{name}.java'
    path.parent.mkdir(parents=True, exist_ok=True)
    package = name.rsplit('/', 1)[0].replace('/', '.')
    path.write_text(f'package {package};\n' + body, encoding='utf-8')
test = work/'src/com/tilepad/ServiceRebindTest.java'
test.write_text('''package com.tilepad;
import android.content.Intent; import android.os.*; import android.util.Log;
import java.lang.reflect.Field;
import java.util.*;
public class ServiceRebindTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void eq(Object expected,Object actual,String m){if(!expected.equals(actual))throw new AssertionError(m+"\\n  expected "+expected+"\\n  actual   "+actual);}
 static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static boolean bound(PreconditioningService s)throws Exception{return (Boolean)field(s,"bound");}
 static boolean requested(PreconditioningService s)throws Exception{return ((PreconditioningSnapshot)field(s,"snapshot")).requested;}
 static KeepWarm kw(PreconditioningService s)throws Exception{return (KeepWarm)field(s,"keepWarm");}
 // Android rule: a client returning to a live service gets onRebind only if the last onUnbind returned true.
 static boolean rebindWanted;
 static void unbind(PreconditioningService s){rebindWanted=s.onUnbind(new Intent());Handler.drain();}
 static void returnToApp(PreconditioningService s){if(rebindWanted)s.onRebind(new Intent());Handler.drain();}
 static PreconditioningService.LocalBinder binder; // the binder handed out by the latest create()
 static PreconditioningService create(FakeVehicle car)throws Exception{VehicleClientFactory.current=car;
  PreconditioningService s=new PreconditioningService();s.onCreate();Handler.drain();
  binder=(PreconditioningService.LocalBinder)s.onBind(new Intent());Handler.drain();return s;}

 // ---- helpers for the keep-warm scenarios ----
 static final String VEH="tilepad-vehicle",BAT="tilepad-battery";
 static long t(){return SystemClock.elapsedRealtime();}
 static void adv(long ms){Handler.advance(ms);}
 static void fresh(){Handler.reset();SystemClock.elapsed=100000;SystemClock.uptime=100000;Log.lines.clear();}
 static void send(PreconditioningService s,Intent i){s.onStartCommand(i,0,1);Handler.drain();}
 static void keepWarm(PreconditioningService s,int minutes){
  send(s,new Intent().setAction(PreconditioningService.KEEP_WARM).putExtra(PreconditioningService.MINUTES,minutes));}
 static void tileStart(PreconditioningService s){send(s,new Intent().setAction(PreconditioningService.START));}
 static void tileStop(PreconditioningService s){send(s,new Intent().setAction(PreconditioningService.STOP));}
 static String begin(long at){return "begin@"+at;} static String stop(long at){return "stop@"+at;} static String maint(long at){return "maintain@"+at;}
 static List<String> maints(long from,long to){List<String> l=new ArrayList<>();for(long x=from;x<=to;x+=5000)l.add(maint(x));return l;}
 @SuppressWarnings("unchecked")
 static List<String> cat(Object... parts){List<String> out=new ArrayList<>();
  for(Object p:parts){if(p instanceof String)out.add((String)p);else out.addAll((List<String>)p);}return out;}
 /** A session that starts at b and goes Active within a second, Off at b+5 s: maintain at b+5 s, stop at b+6 s, ended at b+7 s. */
 static List<String> quick(long b){return cat(begin(b),maint(b+5000),stop(b+6000));}
 static void quickSession(FakeVehicle car){car.indicator=1;adv(5000);car.indicator=0;adv(2000);}
 static List<String> log(FakeVehicle c){return new ArrayList<>(c.log);}
 static String lastBegin(FakeVehicle c){String r="";for(String e:c.log)if(e.startsWith("begin@"))r=e;return r;}
 /** No Start until exactly time at; then exactly one (the one the rules allow), at that tick. */
 static void startsExactlyAt(FakeVehicle car,int already,long at){
  while(t()<at){check(car.starts==already,"a Start went out too early, before +"+(at-t())+" ms of its time; log "+car.log);adv(Math.min(500,at-t()));}
  check(car.starts==already+1,"expected the Start at "+at+" but starts="+car.starts+" (now "+t()+") "+car.log);
  eq(begin(at),lastBegin(car),"time of the Start");
 }
 static void noStartsUntil(FakeVehicle car,int already,long until){
  while(t()<until){check(car.starts==already,"unexpected Start at "+t()+"; log "+car.log);adv(Math.min(500,until-t()));}
  check(car.starts==already,"unexpected Start at "+t());
 }
 /** Every command came from the vehicle thread, every battery read from the battery thread, and the car never saw nonsense. */
 static void done(FakeVehicle car){
  check(car.violations.isEmpty(),"car saw: "+car.violations);
  for(Map.Entry<String,Set<String>> e:car.threads.entrySet())
   eq(new TreeSet<String>(Arrays.asList(e.getKey().equals("info")?BAT:VEH)),e.getValue(),"thread of car call '"+e.getKey()+"'");
 }
 static class Ctx{FakeVehicle car;PreconditioningService s;long a;}
 /** Cold car, service bound then unbound, keep warm armed at a: one Start sent, session not yet driven. */
 static Ctx armedIdle(int minutes)throws Exception{Ctx c=new Ctx();fresh();c.car=new FakeVehicle();c.s=create(c.car);adv(2000);
  c.a=t();keepWarm(c.s,minutes);unbind(c.s);return c;}
 /** ... and the first session driven to its end: the clock is at a+7 s, armed, between sessions, service unbound. */
 static Ctx afterFirstSession(int minutes)throws Exception{Ctx c=armedIdle(minutes);quickSession(c.car);
  eq(quick(c.a),log(c.car),"first session");check(kw(c.s).armed(),"armed between sessions");return c;}
 static void assertAliveForeground(PreconditioningService s,String m){
  check(s.stops==0&&s.isForeground&&s.foregroundText.startsWith("Keeping warm"),m+" (stops="+s.stops+", foreground="+s.isForeground+", text="+s.foregroundText+")");}

 // ---- helpers for scenarios 18-24 ----
 static Intent stopIntent(){return new Intent().setAction(PreconditioningService.STOP);}
 static Intent anywayIntent(){return new Intent().setAction(PreconditioningService.START_ANYWAY);}
 static boolean stopPending(PreconditioningService s)throws Exception{return (Boolean)field(s,"stopPending");}
 /** Armed, session Active, then the car loses READY (or the setting is switched off): the controller cancels, keep warm lets go. */
 static void lostSession(boolean readyLost)throws Exception{
  Ctx c=armedIdle(30);long a=c.a;FakeVehicle car=c.car;PreconditioningService s=c.s;
  car.indicator=1;advTo(a+12000);
  check(binder.snapshot().state==PreconditioningSnapshot.State.ACTIVE&&kw(s).armed(),"Active and armed: "+binder.snapshot().state);
  if(readyLost)car.ready=0;else car.enabled=1;
  adv(1000); // the tick at a+13 s is the first to read the lost value
  List<String> expected=cat(begin(a),maints(a+5000,a+10000),stop(a+13000));
  eq(expected,log(car),"cancelled once, at the tick that read it");
  check(!kw(s).armed()&&kw(s).why()==KeepWarm.Why.NOT_READY&&!binder.keepWarm().armed,"disarmed NOT_READY: "+kw(s).why());
  check(binder.keepWarm().ended.contains(readyLost?"no longer ready":"conditioning is off"),"ended text: "+binder.keepWarm().ended);
  if(readyLost)check(s.notices.shown.isEmpty(),"the car switched off: no notice: "+s.notices.shown);
  else check(s.notices.any().contains("conditioning is off"),"setting switched off: notice: "+s.notices.shown);
  check(s.stops==0&&s.isForeground&&!s.foregroundText.startsWith("Keeping warm"),"still foreground for the cancelling session, no longer Keeping warm: "+s.foregroundText);
  int reads=car.batteryInfoReads;
  car.indicator=0;adv(2000); // the car reports conditioning off: the session is released
  check(!binder.snapshot().requested&&binder.snapshot().state==PreconditioningSnapshot.State.ENDED,"session ended: "+binder.snapshot().state);
  check(binder.endNote().contains(readyLost?"no longer ready":"switched off"),"end note: "+binder.endNote());
  check(s.stops==1&&!s.isForeground,"unbound and idle: the service stops itself and leaves the foreground (stops="+s.stops+", foreground="+s.isForeground+")");
  check(car.batteryInfoReads==reads&&Handler.named(VEH).pending()==0&&Handler.named(BAT).pending()==0,"reader stopped, nothing left scheduled");
  eq(expected,log(car),"no second cancel");
  // READY is back (or the setting on again) and the battery is cold for 30 minutes: the app returns and watches it.
  if(readyLost)car.ready=1;else car.enabled=2;
  returnToApp(s);binder.watch(true);Handler.drain();int before=car.batteryInfoReads;
  adv(1800000);
  check(car.batteryInfoReads-before>=170,"cold samples kept coming: "+(car.batteryInfoReads-before));
  check(binder.snapshot().canStart,"a Start would be possible: "+binder.snapshot().state);
  check(car.starts==1&&!kw(s).armed()&&kw(s).why()==KeepWarm.Why.NOT_READY,"no further Start, still disarmed: starts="+car.starts+" "+kw(s).why());
  eq(expected,log(car),"nothing sent in 30 minutes");
  done(car);
 }
 /** A Stop that arrives (on another thread) while the arming Start is being prepared. nth 0: during the pre-check read; else at the Nth status read. */
 static void armingStop(String how,int nth)throws Exception{
  fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);adv(2000);
  Runnable stop=()->s.onStartCommand(stopIntent(),0,1);
  if(nth==0)car.minimumHook=stop;else{car.readHookFrom=t();car.readHookNth=nth;car.readHook=stop;}
  keepWarm(s,30);
  check(car.minimumHook==null&&car.readHook==null,how+": the Stop did arrive during the Start");
  check(car.starts==0&&car.log.isEmpty(),how+": a Start went out although Stop arrived first: "+car.log);
  check(!kw(s).armed()&&kw(s).why()==KeepWarm.Why.STOPPED&&!binder.keepWarm().armed,how+": disarmed (STOPPED): "+kw(s).why()+" armed="+kw(s).armed());
  check(!requested(s)&&!stopPending(s)&&!Boolean.TRUE.equals(s.prefs.get("requested")),how+": no request, the flag is consumed");
  adv(600000);
  check(car.log.isEmpty()&&!kw(s).armed(),how+": nothing later: "+car.log);
  tileStart(s);
  eq(Arrays.asList(begin(t())),log(car),how+": the next Start by hand is not blocked by the old Stop");
  done(car);
 }
 /** A Stop that arrives while keep warm's own Start is being prepared. nth: which status read from the due tick on (1 = the tick's own). */
 static void automaticStop(int nth)throws Exception{
  Ctx c=afterFirstSession(30);long a=c.a;
  c.car.readHookFrom=a+480000;c.car.readHookNth=nth;c.car.readHook=()->c.s.onStartCommand(stopIntent(),0,1);
  advTo(a+481000);
  check(c.car.readHook==null,"read "+nth+": the Stop did arrive");
  eq(quick(a),log(c.car),"read "+nth+": the Start due at a+480 s was not sent");
  check(!kw(c.s).armed()&&kw(c.s).why()==KeepWarm.Why.STOPPED&&!stopPending(c.s),"read "+nth+": disarmed (STOPPED), flag consumed: "+kw(c.s).why());
  // A Stop that was already there when the tick decided is not an attempt; later it is one that was not sent.
  eq(nth==1?0:1,kw(c.s).automaticStarts(),"read "+nth+": attempts counted");
  adv(1200000);
  eq(quick(a),log(c.car),"read "+nth+": nothing later");
  check(c.car.starts==1,"starts "+c.car.starts);
  done(c.car);
 }

 // ---- helpers for scenarios 25-28 ----
 /** A session runs, the app leaves and comes back; the client's watch(true) arrives before or after onRebind. */
 static void rebindOrder(boolean watchFirst)throws Exception{
  fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);adv(2000);
  tileStart(s);binder.watch(true);Handler.drain();adv(25000);
  check(requested(s)&&car.infoTimes.size()==3,"session running, reading every 10 s: "+car.infoTimes);
  unbind(s);
  int n=car.batteryInfoReads;adv(15000);
  check(s.stops==0&&requested(s)&&car.batteryInfoReads==n&&binder.battery().reader==BatteryStatus.Reader.IDLE,"unbound: the session keeps the service, the reader is off");
  long r;
  if(watchFirst){
   // Android 4.4 hands the client its connection before onRebind runs: its watch(true) comes first.
   binder.watch(true);Handler.drain();adv(5000);
   check(car.batteryInfoReads==n,"watch(true) while nothing is bound reads nothing yet");
   r=t();returnToApp(s);
  }else{
   r=t();returnToApp(s);
   check(bound(s)&&car.batteryInfoReads==n,"rebound but not yet watching: no read");
   adv(5000);check(car.batteryInfoReads==n,"still no read without watch(true)");
   r=t();binder.watch(true);Handler.drain();
  }
  check(car.batteryInfoReads==n+1&&car.infoTimes.get(n)==r,"a read right away: "+car.infoTimes);
  adv(30000);
  eq(Arrays.asList(r,r+10000,r+20000,r+30000),new ArrayList<>(car.infoTimes.subList(n,car.infoTimes.size())),"then one read every 10 s");
  check(binder.battery().reader==BatteryStatus.Reader.READING&&binder.battery().info!=null,"reading");
  done(car);
 }
 /** Keep warm's Start fails before anything is sent (the journal cannot be written): stand down at once. */
 static void journalFailure(boolean arming)throws Exception{
  Ctx c;
  if(arming){c=new Ctx();fresh();c.car=new FakeVehicle();c.s=create(c.car);adv(2000);c.a=t();c.s.failCommits=1;keepWarm(c.s,30);
   check(c.s.failCommits==0,"the journal write was tried and failed");}
  else{c=afterFirstSession(30);c.s.failCommits=1;advTo(c.a+481000);
   check(c.s.failCommits==0,"the journal write was tried and failed");}
  PreconditioningService s=c.s;FakeVehicle car=c.car;long a=c.a;
  List<String> expected=arming?new ArrayList<String>():quick(a);
  eq(expected,log(car),"no begin recorded");
  check(!kw(s).armed()&&kw(s).why()==KeepWarm.Why.CAR_ERROR&&!binder.keepWarm().armed,"disarmed at once, CAR_ERROR: "+kw(s).why()+" armed="+kw(s).armed());
  check(!requested(s)&&!Boolean.TRUE.equals(s.prefs.get("requested")),"nothing requested, nothing journalled");
  check(binder.keepWarm().ended.length()>0,"the driver is told why: "+binder.keepWarm().ended);
  check(!binder.quietSession(),"not quiet after the error");
  check(s.notices.any().contains("request to the car failed"),"notice: "+s.notices.shown);
  // Long cold run afterwards: the app is open and watching; nothing may start.
  if(!arming)returnToApp(s); // keep warm had been left running with the app closed
  binder.watch(true);Handler.drain();int before=car.batteryInfoReads;
  adv(1800000);
  check(car.batteryInfoReads-before>=170,"cold samples kept coming: "+(car.batteryInfoReads-before));
  check(car.starts==(arming?0:1)&&!kw(s).armed(),"no Start later: starts="+car.starts);
  eq(expected,log(car),"nothing sent in 30 minutes");
  done(car);
 }

 interface Body{void run()throws Exception;}
 static int passed,failed;
 static void scenario(String name,boolean suspectedBug,Body b){
  try{b.run();passed++;System.out.println("PASS "+name);}
  catch(Throwable t){failed++;System.out.println((suspectedBug?"SUSPECTED BUG - FAIL ":"FAIL ")+name+": "+t);}
 }

 public static void main(String[] args)throws Exception{
  // ===== the six original scenarios; same assertions as before, plus the exact commands the car received =====
  // (they run first, share one clock and are never armed: this is also scenario 17, not armed = as before)
  scenario("original 1: Start uses one temperature precheck",false,()->{
   car1=new FakeVehicle(); s1=create(car1); start1=t();
   check(bound(s1)&&car1.reads==1&&car1.temperatureReads==0,"bind only refreshes status");
   s1.onStartCommand(new Intent().setAction(PreconditioningService.START),0,1);Handler.drain();
   check(car1.starts==1&&requested(s1)&&car1.temperatureReads==1,"Start uses one temperature precheck");
   eq(Arrays.asList(begin(start1)),log(car1),"17: exactly one Start, nothing else");
   check(!kw(s1).armed()&&!s1.foregroundText.startsWith("Keeping warm")&&car1.batteryInfoReads==0,"17: not armed, battery not read");
  });
  scenario("original 2: session keeps the existing service alive; status continues without telemetry polling",false,()->{
   int beforeLeave=car1.reads; unbind(s1);
   check(!bound(s1)&&s1.stops==0,"session keeps the existing service alive");
   Handler.advance(35_000); check(car1.reads>beforeLeave&&car1.temperatureReads==1,"status continues, no telemetry polling");
   eq(cat(begin(start1),maints(start1+5000,start1+35000)),log(car1),"17: Start, then one maintenance every 5 s, no other command");
  });
  scenario("original 3: rebind immediately refreshes the existing session",false,()->{
   int beforeReturn=car1.reads;returnToApp(s1);
   check(bound(s1)&&car1.reads>beforeReturn&&requested(s1),"rebind immediately refreshes the existing session");
   eq(cat(begin(start1),maints(start1+5000,start1+35000)),log(car1),"17: rebind sends no command");
   check(s1.isForeground&&!s1.foregroundText.startsWith("Keeping warm")&&car1.batteryInfoReads==0,"17: ordinary notification, battery not read");
   done(car1);
  });
  scenario("original 4: idle service stops after unbind",false,()->{
   FakeVehicle idle=new FakeVehicle(); PreconditioningService t=create(idle); unbind(t);
   check(t.stops==1&&!bound(t),"idle service stops after unbind");
   check(idle.log.isEmpty()&&!t.isForeground,"17: no command, no notification");
   done(idle);
  });
  scenario("original 5: warm advisory needs explicit Start anyway",false,()->{
   FakeVehicle warm=new FakeVehicle();warm.minimum=21;PreconditioningService w=create(warm);
   w.onStartCommand(new Intent().setAction(PreconditioningService.START),0,1);Handler.drain();
   check(warm.starts==0&&!requested(w),"warm advisory does not claim ownership");
   w.onStartCommand(new Intent().setAction(PreconditioningService.START),0,1);Handler.drain();
   check(warm.starts==0&&warm.temperatureReads==1,"duplicate Start cannot override");
   check(warm.log.isEmpty(),"17: nothing sent while the notice is shown");
   w.onStartCommand(new Intent().setAction(PreconditioningService.START_ANYWAY),0,1);Handler.drain();
   check(warm.starts==1&&requested(w),"only explicit Start anyway requests conditioning");
   eq(Arrays.asList(begin(t())),log(warm),"17: exactly one Start, after the explicit consent");
   done(warm);
  });
  scenario("original 6: READY is rechecked after the temperature read",false,()->{
   FakeVehicle changed=new FakeVehicle();changed.loseReady=true;PreconditioningService c=create(changed);
   c.onStartCommand(new Intent().setAction(PreconditioningService.START),0,1);Handler.drain();
   check(changed.starts==0&&!requested(c),"READY is rechecked after the temperature read");
   check(changed.log.isEmpty(),"17: nothing sent");
   done(changed);
  });

  // ===== keep warm =====
  scenario("1 arm 30 min idle and cold: one Start; unbind keeps the service alive and in the foreground between sessions",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);adv(2000);long a=t();
   check(!kw(s).armed()&&!binder.keepWarm().armed,"not armed before");
   keepWarm(s,30);
   eq(Arrays.asList(begin(a)),log(car),"arming sends exactly one Start");
   check(kw(s).armed()&&kw(s).minutes()==30&&binder.keepWarm().armed&&binder.keepWarm().minutes==30,"armed for 30 minutes");
   assertAliveForeground(s,"armed and bound");
   unbind(s);
   assertAliveForeground(s,"unbound during the session");
   car.indicator=1;adv(5000);
   check(binder.snapshot().state==PreconditioningSnapshot.State.ACTIVE,"car Active");
   car.indicator=0;adv(2000);
   eq(quick(a),log(car),"one Start, one stop for the session");
   check(!binder.snapshot().requested&&kw(s).armed(),"session over, still armed");
   assertAliveForeground(s,"between sessions");
   check(s.foregroundText.startsWith("Keeping warm"),"notification says Keeping warm: "+s.foregroundText);
   advTo(a+300000);
   assertAliveForeground(s,"five minutes later, still between sessions");
   eq(quick(a),log(car),"no other command");
   for(int i=1;i<s.foregroundTexts.size();i++)check(!s.foregroundTexts.get(i).equals(s.foregroundTexts.get(i-1)),"notification text repeated: "+s.foregroundTexts.get(i));
   done(car);
  });
  scenario("2 second Start only after 8 min since the first Start, 5 min since the session ended and a 180 s cold run",false,()->{
   Ctx c=afterFirstSession(30);long a=c.a;
   // the three conditions are met at a+180 s (cold run), a+307 s (rest), a+480 s (gap since the first Start)
   startsExactlyAt(c.car,1,a+480000);
   eq(cat(quick(a),begin(a+480000)),log(c.car),"commands");
   check(kw(c.s).automaticStarts()==1,"counted as automatic");
   for(int i=1;i<c.car.infoTimes.size();i++)eq(20000L,c.car.infoTimes.get(i)-c.car.infoTimes.get(i-1),"cold sample every 20 s");
   assertAliveForeground(c.s,"second session open");
   done(c.car);
  });
  scenario("2b the 5 min rest after a long session is the binding condition",false,()->{
   Ctx c=armedIdle(30);long a=c.a;
   c.car.indicator=1;advTo(a+200000);c.car.indicator=0;adv(2000); // stop at a+201 s, ended at a+202 s
   eq(cat(begin(a),maints(a+5000,a+200000),stop(a+201000)),log(c.car),"long first session");
   startsExactlyAt(c.car,1,a+502000);
   done(c.car);
  });
  scenario("2c a battery at 19 is not cold; the 180 s cold run restarts when it drops to 18",false,()->{
   Ctx c=afterFirstSession(30);long a=c.a;
   advTo(a+10000);c.car.setTemp(19);
   noStartsUntil(c.car,1,a+700000); // gap and rest long satisfied; no cold run at 19
   advTo(a+705000);c.car.setTemp(18);
   // The first cold sample is a+720 s. The cold run is measured between readings, so the Start waits for the sample
   // that completes 180 s of them: a+900 s. The worker's tick of an instant runs before the reader's sample of that
   // same instant is delivered (drain: vehicle handler first), so the first tick that can see it is a+901 s.
   startsExactlyAt(c.car,1,a+901000);
   done(c.car);
  });
  scenario("2d samples with a gap over 45 s, or too old, never allow a Start; the cold run restarts after the gap",false,()->{
   Ctx c=afterFirstSession(30);long a=c.a;
   advTo(a+425000);c.car.sample=null; // reads at a+440, 460, 480, 500 s fail (4 in a row: reader keeps going)
   advTo(a+510000);c.car.sample=15;   // a+520 s is the next good sample, 100 s after the last one
   noStartsUntil(c.car,1,a+699500);   // due at 480 s otherwise; with the gap it restarts the 180 s run at a+520 s
   // The sample that completes 180 s of cold readings is the one at a+700 s; it is delivered after that instant's tick
   // (vehicle handler first), so the Start goes out at the next tick, a+701 s, and not before.
   startsExactlyAt(c.car,1,a+701000);
   check(kw(c.s).armed()&&!c.car.log.isEmpty(),"still armed");
   done(c.car);
  });
  scenario("3 STOP between sessions while armed and unbound: nothing sent, disarmed, the service stops itself",false,()->{
   Ctx c=afterFirstSession(30);long a=c.a;adv(30000);
   assertAliveForeground(c.s,"before STOP");
   int reads=c.car.batteryInfoReads;
   tileStop(c.s);
   eq(quick(a),log(c.car),"no command sent by STOP");
   check(!kw(c.s).armed()&&kw(c.s).why()==KeepWarm.Why.STOPPED&&!binder.keepWarm().armed,"disarmed, why STOPPED");
   check(c.s.stops==1&&!c.s.isForeground,"service stops itself and leaves the foreground");
   adv(1200000);
   eq(quick(a),log(c.car),"nothing later either");
   check(c.car.batteryInfoReads==reads,"battery reader stopped");
   check(Handler.named(VEH).pending()==0&&Handler.named(BAT).pending()==0,"nothing left scheduled");
   done(c.car);
  });
  scenario("4 STOP during a keep-warm session: one stop, disarmed, no further Start for 30 min of cold samples",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);binder.watch(true);Handler.drain();adv(2000);
   long a=t();keepWarm(s,30);car.indicator=1;adv(5000);
   tileStop(s);
   check(!kw(s).armed()&&kw(s).why()==KeepWarm.Why.STOPPED,"disarmed first");
   eq(cat(begin(a),maint(a+5000),stop(a+5000)),log(car),"one stop");
   adv(3000);
   eq(cat(begin(a),maint(a+5000),stop(a+5000)),log(car),"the car still reports Active: no second stop, no maintenance");
   car.indicator=0;adv(2000);
   check(!binder.snapshot().requested,"session ended");
   int reads=car.batteryInfoReads;
   adv(1800000);
   eq(cat(begin(a),maint(a+5000),stop(a+5000)),log(car),"no Start in the next 30 minutes");
   check(car.batteryInfoReads-reads>=170,"cold samples kept coming every 10 s: "+(car.batteryInfoReads-reads));
   check(!kw(s).armed()&&binder.keepWarm().ended.length()>0,"ended text: "+binder.keepWarm().ended);
   check(s.stops==0&&!s.isForeground,"bound service stays, no notification");
   done(car);
  });
  scenario("5a time limit while a session runs: disarmed at 30:00, the session goes on under ordinary rules",false,()->{
   Ctx c=armedIdle(30);long a=c.a;
   c.car.indicator=1;
   advTo(a+1799500);check(kw(c.s).armed(),"armed at 29:59.5");
   adv(500);
   check(!kw(c.s).armed()&&kw(c.s).why()==KeepWarm.Why.TIME_UP,"disarmed at 30:00 (TIME_UP)");
   check(binder.snapshot().requested&&binder.snapshot().state==PreconditioningSnapshot.State.ACTIVE,"session continues");
   check(c.s.stops==0&&c.s.isForeground&&!c.s.foregroundText.startsWith("Keeping warm"),"ordinary notification now: "+c.s.foregroundText);
   check(c.car.infoTimes.get(c.car.infoTimes.size()-1)<=a+1800000,"battery reader stopped with it");
   advTo(a+1810000);c.car.indicator=0;adv(2000);
   eq(cat(begin(a),maints(a+5000,a+1810000),stop(a+1811000)),log(c.car),"ordinary maintenance, then the ordinary stop; no Start");
   check(c.s.stops==1&&!c.s.isForeground,"service stops once the session is over");
   done(c.car);
  });
  scenario("5c time limit during keep warm's OWN session: nothing is sent to the car at 30:00; the session runs to its ordinary end",false,()->{
   Ctx c=afterFirstSession(30);long a=c.a;
   startsExactlyAt(c.car,1,a+480000);
   check(binder.quietSession(),"keep warm's own session");
   c.car.indicator=1;adv(5000);
   check(binder.snapshot().state==PreconditioningSnapshot.State.ACTIVE,"keep warm's own session Active");
   advTo(a+1799500);check(kw(c.s).armed(),"armed at 29:59.5");
   int stopsBefore=0;for(String e:c.car.log)if(e.startsWith("stop@"))stopsBefore++;
   adv(500);
   check(!kw(c.s).armed()&&kw(c.s).why()==KeepWarm.Why.TIME_UP,"disarmed at 30:00 (TIME_UP)");
   adv(30000);
   int stopsAfter=0;for(String e:c.car.log)if(e.startsWith("stop@"))stopsAfter++;
   check(stopsAfter==stopsBefore,"no stop sent at the time limit: "+c.car.log);
   check(binder.snapshot().requested&&binder.snapshot().state==PreconditioningSnapshot.State.ACTIVE,"the session goes on: "+binder.snapshot().state);
   check(c.s.isForeground&&c.s.stops==0,"service stays up for the running session");
   check(binder.keepWarm().ended.contains("ended after 30 minutes"),"says why keep warm ended: "+binder.keepWarm().ended);
   check(!c.s.foregroundText.startsWith("Keeping warm"),"the ongoing notification no longer says Keeping warm: "+c.s.foregroundText);
   check(c.s.notices.any().contains("ended after 30 minutes"),"a plain notification says keep warm ended: "+c.s.notices.shown);
   check(!binder.quietSession(),"from now on an ordinary session: its result may pop a card");
   int readsAtEnd=c.car.batteryInfoReads;
   noStartsUntil(c.car,2,a+1800000+20*60000L); // still heating for 20 more minutes: no new Start, nothing sent but maintenance
   check(c.car.batteryInfoReads==readsAtEnd,"battery reads stopped with keep warm while unbound: "+(c.car.batteryInfoReads-readsAtEnd));
   for(String e:c.car.log)check(!e.startsWith("begin@")||Long.parseLong(e.substring(6))<=a+480000,"no Start after the limit: "+e);
   long off=t();c.car.indicator=0;adv(2000); // the car finishes heating: ordinary release
   int stopsEnd=0;for(String e:c.car.log)if(e.startsWith("stop@"))stopsEnd++;
   check(stopsEnd==stopsBefore+1,"exactly one ordinary release when the car stopped heating: "+c.car.log);
   check(!binder.snapshot().requested&&!binder.quietSession(),"session over, flag cleared");
   check(c.s.stops==1&&!c.s.isForeground,"service stops itself once the session is over");
   check(c.s.notices.any().contains("ended after 30 minutes"),"the notice outlives the service: "+c.s.notices.shown);
   returnToApp(c.s);tileStart(c.s);
   check(c.s.notices.shown.isEmpty()&&binder.keepWarm().ended.isEmpty(),"a Start clears the notice and the reason: "+c.s.notices.shown);
   done(c.car);
  });
  scenario("5d start limit at 4 h: eight automatic Starts, then no ninth",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);binder.watch(true);Handler.drain();adv(2000);
   long a=t();keepWarm(s,240);quickSession(car);
   check(kw(s).startLimit()==8,"limit 8 at 4 h: "+kw(s).startLimit());
   for(int i=1;i<=8;i++){long b=a+480000L*i;startsExactlyAt(car,i,b);quickSession(car);}
   check(kw(s).automaticStarts()==8,"eight automatic Starts");
   adv(2000);
   check(!kw(s).armed()&&kw(s).why()==KeepWarm.Why.START_LIMIT,"disarmed START_LIMIT after the eighth: "+kw(s).why());
   noStartsUntil(car,9,a+480000L*10);
   done(car);
  });
  scenario("5e head unit slept during keep warm's own Active session: disarmed SLEPT, nothing sent, the session goes on",false,()->{
   Ctx c=afterFirstSession(30);long a=c.a;
   startsExactlyAt(c.car,1,a+480000);
   c.car.indicator=1;adv(5000);
   int stops=0;for(String e:c.car.log)if(e.startsWith("stop@"))stops++;
   Handler.sleep(30000);adv(1000);
   check(!kw(c.s).armed()&&kw(c.s).why()==KeepWarm.Why.SLEPT,"disarmed SLEPT: "+kw(c.s).why());
   adv(10000);
   int after=0;for(String e:c.car.log)if(e.startsWith("stop@"))after++;
   check(after==stops,"no stop sent: "+c.car.log);
   check(binder.snapshot().requested,"the session goes on");
   check(c.s.notices.any().contains("stopped responding"),"plain notification says why: "+c.s.notices.shown);
   c.car.indicator=0;adv(2000);
   done(c.car);
  });
  scenario("5f battery unreadable for 5 minutes during keep warm's own session: disarmed NO_TEMPERATURE, nothing sent, the session goes on",false,()->{
   Ctx c=afterFirstSession(30);long a=c.a;
   startsExactlyAt(c.car,1,a+480000);
   c.car.indicator=1;adv(5000);
   int stops=0;for(String e:c.car.log)if(e.startsWith("stop@"))stops++;
   c.car.sample=null;
   for(int i=0;i<400&&kw(c.s).armed();i++)adv(1000);
   check(!kw(c.s).armed()&&kw(c.s).why()==KeepWarm.Why.NO_TEMPERATURE,"disarmed NO_TEMPERATURE: "+kw(c.s).why());
   adv(10000);
   int after=0;for(String e:c.car.log)if(e.startsWith("stop@"))after++;
   check(after==stops&&binder.snapshot().requested,"nothing sent, session goes on: "+c.car.log);
   check(c.s.notices.any().contains("temperature"),"notice: "+c.s.notices.shown);
   c.car.sample=15;c.car.indicator=0;adv(2000);
   done(c.car);
  });
  scenario("5l keep warm ends while its own Start still waits: the session becomes ordinary, then goes Active",false,()->{
   Ctx c=afterFirstSession(30);long a=c.a;
   startsExactlyAt(c.car,1,a+480000);
   check(binder.quietSession()&&binder.snapshot().state==PreconditioningSnapshot.State.WAITING,"waiting, quiet");
   Handler.sleep(30000);adv(1000);
   check(!kw(c.s).armed()&&kw(c.s).why()==KeepWarm.Why.SLEPT,"ended (SLEPT) while its Start waits: "+kw(c.s).why());
   check(!binder.quietSession()&&binder.snapshot().requested,"ordinary session now, still requested");
   c.car.indicator=1;adv(3000);
   check(binder.snapshot().state==PreconditioningSnapshot.State.ACTIVE,"Active");
   c.car.indicator=0;adv(2000);
   done(c.car);
  });
  scenario("5h the driver's Stop on a session that outlived keep warm: exactly one stop, at once",false,()->{
   Ctx c=afterFirstSession(30);long a=c.a;
   startsExactlyAt(c.car,1,a+480000);
   c.car.indicator=1;advTo(a+1800000+1000);
   check(!kw(c.s).armed()&&binder.snapshot().requested,"keep warm over, session still running");
   int stops=0;for(String e:c.car.log)if(e.startsWith("stop@"))stops++;
   long at=t();tileStop(c.s);
   int after=0;for(String e:c.car.log)if(e.startsWith("stop@"))after++;
   check(after==stops+1&&c.car.log.contains(stop(at)),"one stop at the Stop: "+c.car.log);
   c.car.indicator=0;adv(2000);
   check(!binder.snapshot().requested,"released");
   check(binder.endNote().contains("You turned preconditioning off"),"end note: "+binder.endNote());
   done(c.car);
  });
  scenario("5i re-arm while a session from the earlier arming still runs: no Start at arming, a fresh limit, spacing from that session's Start",false,()->{
   Ctx c=afterFirstSession(30);long a=c.a;
   startsExactlyAt(c.car,1,a+480000);
   c.car.indicator=1;advTo(a+1800000+1000);
   check(!kw(c.s).armed()&&binder.snapshot().requested,"keep warm over, session still running");
   returnToApp(c.s);keepWarm(c.s,240);unbind(c.s);
   check(kw(c.s).armed()&&kw(c.s).startLimit()==8&&kw(c.s).automaticStarts()==0,"re-armed for 4 h, limit 8");
   check(c.car.starts==2,"no Start at arming while a session runs: "+c.car.log);
   long end=t()+60000;advTo(end);c.car.indicator=0;adv(2000); // the car finishes; ordinary release
   check(!binder.snapshot().requested,"session over");
   long due=Math.max(a+480000+480000,end+300000);
   noStartsUntil(c.car,2,due-1000);
   done(c.car);
  });
  scenario("5j re-arm on a warm battery soon after a session that outlived keep warm ended: the 5-minute rest still holds",false,()->{
   Ctx c=afterFirstSession(30);long a=c.a;
   startsExactlyAt(c.car,1,a+480000);
   c.car.indicator=1;advTo(a+1800000+1000);
   check(!kw(c.s).armed(),"keep warm over");
   c.car.indicator=0;adv(2000);
   check(!binder.snapshot().requested,"that session ended while disarmed");
   long ended=t();
   returnToApp(c.s);c.car.setTemp(22);adv(11000);keepWarm(c.s,60);unbind(c.s);
   check(kw(c.s).armed(),"armed");
   int starts=c.car.starts;
   check(starts==2,"the warm notice: nothing sent at arming: "+c.car.log);
   long armT=t();c.car.setTemp(10);
   noStartsUntil(c.car,starts,armT+300000-1000);
   for(int i=0;i<180&&c.car.starts==starts;i++)adv(1000);
   check(c.car.starts==starts+1,"a Start once the rest is over (and the warm notice reset): "+c.car.log);
   done(c.car);
  });
  scenario("5k durations: 15 min to 2 h by 15, then by 30 up to 6 h; anything else leaves it unarmed and sends nothing",false,()->{
   for(int m:new int[]{0,10,50,135,165,345,375,390}){
    fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);adv(2000);
    keepWarm(s,m);
    check(!kw(s).armed()&&car.starts==0,m+" min must be refused: armed="+kw(s).armed()+" log="+car.log);
   }
   for(int[] m:new int[][]{{15,4},{120,4},{150,5},{360,12}}){
    fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);adv(2000);
    keepWarm(s,m[0]);
    check(kw(s).armed()&&kw(s).startLimit()==m[1],m[0]+" min: armed with limit "+m[1]+", got "+kw(s).startLimit());
   }
  });
  scenario("5b time limit between sessions: disarmed at 30:00, no Start afterwards although one was due at 32:00",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);binder.watch(true);Handler.drain();adv(2000);
   long a=t();keepWarm(s,30);quickSession(car);
   List<String> expected=cat(quick(a));
   for(int i=1;i<=3;i++){long b=a+480000L*i;startsExactlyAt(car,i,b);quickSession(car);expected.addAll(quick(b));}
   advTo(a+1799500);check(kw(s).armed(),"armed at 29:59.5");
   adv(500);
   check(!kw(s).armed()&&kw(s).why()==KeepWarm.Why.TIME_UP,"disarmed at 30:00");
   noStartsUntil(car,4,a+1980000); // the next one would have been due at a+1920 s
   eq(expected,log(car),"four sessions, no fifth");
   check(kw(s).automaticStarts()==3&&s.stops==0&&!s.isForeground,"bound service, no notification");
   done(car);
  });
  scenario("6 sleep(30 s) while armed between sessions: the next tick disarms (SLEPT), no Start even with a long cold run",false,()->{
   Ctx c=afterFirstSession(30);long a=c.a;
   advTo(a+470000);check(c.car.starts==1&&kw(c.s).armed(),"cold run is 470 s long, Start due at 480 s");
   Handler.sleep(30000);
   check(SystemClock.elapsed-SystemClock.uptime==30000,"clocks apart by exactly the sleep");
   adv(1500);
   check(!kw(c.s).armed()&&kw(c.s).why()==KeepWarm.Why.SLEPT,"disarmed: "+kw(c.s).why());
   eq(quick(a),log(c.car),"nothing sent");
   int reads=c.car.batteryInfoReads;
   adv(1200000);
   eq(quick(a),log(c.car),"still nothing 20 minutes later");
   check(c.car.batteryInfoReads==reads&&c.s.stops==1&&!c.s.isForeground,"reader stopped, service stopped");
   done(c.car);
  });
  scenario("7a worker blocked 30 s by the car (both clocks): disarmed, nothing sent",false,()->{
   Ctx c=afterFirstSession(30);long a=c.a;
   advTo(a+99500);c.car.blockAt=a+100000;
   adv(500); // the tick at a+100 s blocks in the car's read for 30 s
   check(t()==a+130000&&SystemClock.uptime==SystemClock.elapsed,"the block moved both clocks");
   adv(2000);
   check(!kw(c.s).armed()&&kw(c.s).why()==KeepWarm.Why.SLEPT,"disarmed: "+kw(c.s).why());
   adv(1200000);
   eq(quick(a),log(c.car),"nothing sent");
   check(c.s.stops==1,"service stops");
   done(c.car);
  });
  // SUSPECTED BUG (if it fails): the tick that is blocked in the car's read still acts on the time it took before the block
  scenario("7b worker blocked 30 s in the very tick a Start is due: disarmed, nothing sent",true,()->{
   Ctx c=afterFirstSession(30);long a=c.a;
   advTo(a+479500);c.car.blockAt=a+480000;
   adv(500);adv(2000);
   check(!kw(c.s).armed()&&kw(c.s).why()==KeepWarm.Why.SLEPT,"disarmed: "+kw(c.s).why());
   eq(quick(a),log(c.car),"nothing sent, Start was due when the car blocked");
   done(c.car);
  });
  scenario("8 battery reads fail 5 times in a row while armed: reader stopped, disarmed (NO_TEMPERATURE), no Start",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();car.sample=null;PreconditioningService s=create(car);adv(2000);
   long a=t();keepWarm(s,30);unbind(s);
   check(car.starts==1,"the pre-check read still works: arming Start sent");
   quickSession(car);
   advTo(a+79500);check(kw(s).armed()&&car.batteryInfoReads==4,"4 failed reads so far: armed, reads="+car.batteryInfoReads);
   advTo(a+82000);
   check(car.batteryInfoReads==5&&!kw(s).armed()&&kw(s).why()==KeepWarm.Why.NO_TEMPERATURE,"after the 5th: "+kw(s).why()+", reads="+car.batteryInfoReads);
   check(binder.battery().reader==BatteryStatus.Reader.STOPPED,"reader says stopped: "+binder.battery().reader);
   adv(600000);
   check(car.batteryInfoReads==5,"no sixth read");
   eq(quick(a),log(car),"no Start");
   check(s.stops==1&&!s.isForeground,"service stops");
   done(car);
  });
  scenario("9 arm while the battery is warm: no Start, notice shown, armed; one Start 180 s after it cools, never overridden",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();car.setTemp(22);PreconditioningService s=create(car);adv(2000);
   long a=t();keepWarm(s,30);
   check(car.log.isEmpty(),"no Start at arming");
   check(binder.snapshot().state==PreconditioningSnapshot.State.WARM&&binder.snapshot().warmRemainingSeconds==10,"warm notice: "+binder.snapshot().state);
   check(kw(s).armed()&&binder.keepWarm().armed,"still armed");
   unbind(s);assertAliveForeground(s,"armed, no session");
   noStartsUntil(car,0,a+65000);
   check(car.log.isEmpty()&&kw(s).armed(),"warm for a minute: nothing sent, still armed");
   car.setTemp(15); // the next read is at a+80 s
   // First cold sample a+80 s; the sample at a+260 s completes 180 s of readings and is delivered after that instant's
   // tick (vehicle handler first), so the first tick that can send the Start is a+261 s.
   startsExactlyAt(car,0,a+261000);
   eq(Arrays.asList(begin(a+261000)),log(car),"a single ordinary Start");
   check(kw(s).automaticStarts()==1,"automatic");
   done(car);
  });
  scenario("10 arm while a session is already Active: no extra Start; its end is followed",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);adv(2000);
   long start=t();tileStart(s);car.indicator=1;adv(3000);
   keepWarm(s,30);
   eq(Arrays.asList(begin(start)),log(car),"no extra Start at arming");
   check(kw(s).armed()&&kw(s).automaticStarts()==0,"armed");
   unbind(s);adv(2000); // maintenance at start+5 s
   car.indicator=0;long x=t();adv(2000); // stop at x+1 s, session ended at x+2 s
   check(!binder.snapshot().requested&&kw(s).armed(),"the session ended, still armed");
   startsExactlyAt(car,1,start+480000); // 8 min after the session's own Start, which is later than 5 min after its end
   eq(cat(begin(start),maint(start+5000),stop(x+1000),begin(start+480000)),log(car),"commands");
   done(car);
  });
  scenario("10b session started by hand before arming: the next Start is still 8 min after that Start",true,()->{
   fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);adv(2000);
   long start=t();tileStart(s);car.indicator=1;adv(60000);
   keepWarm(s,30);unbind(s);
   advTo(start+120000);car.indicator=0;adv(2000); // session over at start+122 s
   while(car.starts<2&&t()<start+900000)adv(500);
   check(car.starts==2,"a second Start came");
   check(t()>=start+480000,"second Start only "+(t()-start)/1000+" s after the first one, the rule says at least 480 s");
   done(car);
  });
  scenario("11 manual START between sessions: an ordinary Start, not counted against the 4 automatic ones",false,()->{
   Ctx c=afterFirstSession(120);long a=c.a;
   advTo(a+60000);long m=t();
   tileStart(c.s);
   eq(cat(quick(a),begin(m)),log(c.car),"the tile's Start is sent");
   check(kw(c.s).automaticStarts()==0,"not counted");
   quickSession(c.car);
   List<String> expected=cat(quick(a),quick(m));
   for(int i=1;i<=4;i++){ // 8 min after the manual Start, as for any Start
    long b=m+480000L*i;startsExactlyAt(c.car,i+1,b);quickSession(c.car);expected.addAll(quick(b));
   }
   check(!kw(c.s).armed()&&kw(c.s).why()==KeepWarm.Why.START_LIMIT&&c.car.starts==6,"1 arming + 1 manual + 4 automatic, then the limit: "+kw(c.s).why()+" starts="+c.car.starts);
   check(kw(c.s).automaticStarts()==4,"4 automatic");
   eq(expected,log(c.car),"commands");
   adv(1200000);check(c.car.starts==6,"no seventh");
   done(c.car);
  });
  scenario("12 four automatic Starts then no fifth; START_LIMIT once the last session has ended",false,()->{
   Ctx c=afterFirstSession(120);long a=c.a;
   List<String> expected=cat(quick(a));
   for(int i=1;i<=4;i++){
    long b=a+480000L*i;startsExactlyAt(c.car,i,b);
    if(i<4){quickSession(c.car);}
    else{c.car.indicator=1;adv(5000);c.car.indicator=0;adv(1000);check(kw(c.s).armed(),"still armed while the last session is open");adv(1000);}
    expected.addAll(quick(b));
   }
   check(!kw(c.s).armed()&&kw(c.s).why()==KeepWarm.Why.START_LIMIT,"disarmed once the last session ended: "+kw(c.s).why());
   check(kw(c.s).automaticStarts()==4&&c.car.starts==5,"1 arming + 4 automatic");
   eq(expected,log(c.car),"commands");
   check(c.s.stops==1&&!c.s.isForeground,"service stops");
   adv(1200000);check(c.car.starts==5,"no fifth");
   done(c.car);
  });
  scenario("13 two sessions that never reach Active (activation timeout): disarmed NO_HEAT",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();car.timeoutMs=60000;PreconditioningService s=create(car);adv(2000);
   long a=t();keepWarm(s,30);unbind(s);
   advTo(a+62000);
   eq(cat(begin(a),maints(a+5000,a+55000),stop(a+60000)),log(car),"first request cancelled by its timeout");
   check(kw(s).armed(),"one no-heat session is not enough");
   startsExactlyAt(car,1,a+480000);
   advTo(a+540500);check(kw(s).armed()&&car.starts==2,"second session still open");
   adv(500);
   check(!kw(s).armed()&&kw(s).why()==KeepWarm.Why.NO_HEAT,"disarmed: "+kw(s).why());
   eq(cat(begin(a),maints(a+5000,a+55000),stop(a+60000),begin(a+480000),maints(a+485000,a+535000),stop(a+540000)),log(car),"commands");
   adv(1200000);check(car.starts==2&&s.stops==1,"no third Start, service stopped");
   done(car);
  });
  scenario("14 new service instance after onDestroy while armed with a request open: cancel, no Start, not armed",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s1=create(car);adv(2000);
   long a=t();keepWarm(s1,30);car.indicator=1;adv(5000);
   check(s1.prefs.get("requested")==Boolean.TRUE,"journal says requested");
   s1.onDestroy();Handler.drain();
   VehicleClientFactory.current=car;PreconditioningService s2=new PreconditioningService();
   s2.prefs.putAll(s1.prefs);s2.onCreate();Handler.drain();
   binder=(PreconditioningService.LocalBinder)s2.onBind(new Intent());Handler.drain();
   eq(cat(begin(a),maint(a+5000),stop(t())),log(car),"recovery sends a cancel and no Start");
   check(!kw(s2).armed()&&!binder.keepWarm().armed,"new instance is never armed");
   adv(3000);car.indicator=0;adv(3000);
   check(!binder.snapshot().requested&&s2.prefs.get("requested")==Boolean.FALSE,"cancel confirmed");
   adv(1200000);
   eq(cat(begin(a),maint(a+5000),stop(a+5000)),log(car),"cold samples for 20 minutes, no Start");
   check(car.starts==1&&!s2.isForeground&&s2.stops==0,"bound service, no notification");
   done(car);
  });
  scenario("14b same, destroyed between sessions: the new instance is not armed and sends nothing",false,()->{
   Ctx c=afterFirstSession(30);long a=c.a;
   c.s.onDestroy();Handler.drain();
   VehicleClientFactory.current=c.car;PreconditioningService s2=new PreconditioningService();
   s2.prefs.putAll(c.s.prefs);s2.onCreate();Handler.drain();
   binder=(PreconditioningService.LocalBinder)s2.onBind(new Intent());Handler.drain();
   check(!kw(s2).armed(),"not armed");
   adv(1800000);
   eq(quick(a),log(c.car),"no command at all, no Start in 30 minutes");
   done(c.car);
  });
  scenario("15 watch(true) reads every 10 s; watch(false) or unbind stops it; a result after onDestroy is ignored",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);
   check(Handler.named(VEH)!=null&&Handler.named(BAT)!=null&&Handler.named(VEH)!=Handler.named(BAT),"two distinct threads");
   check(Handler.named(VEH).looper.name.equals(VEH)&&Handler.named(BAT).looper.name.equals(BAT),"named");
   adv(2000);check(car.batteryInfoReads==0,"bound but not watching: no battery read");
   long w=t();binder.watch(true);Handler.drain();adv(35000);
   eq(Arrays.asList(w,w+10000,w+20000,w+30000),car.infoTimes,"one read every 10 s");
   check(binder.history().size()==4&&binder.history().get(3).at==w+30000&&binder.history().get(3).minimumC==15,"history");
   check(binder.battery().info.minimumC==15&&binder.battery().info.chargeRaw==96&&binder.battery().reader==BatteryStatus.Reader.READING,"battery status");
   check(!kw(s).armed()&&car.log.isEmpty(),"watching alone never sends a command");
   car.stuck=true;adv(2000);check(binder.battery().reader==BatteryStatus.Reader.STUCK,"stuck reader reported");
   car.stuck=false;
   binder.watch(false);Handler.drain();int n=car.batteryInfoReads;
   adv(60000);check(car.batteryInfoReads==n&&binder.battery().reader==BatteryStatus.Reader.IDLE,"watch(false) stops the reads");
   check(s.stops==0&&Handler.named(BAT).pending()==0,"service stays (bound), nothing scheduled on the battery thread");
   // unbind stops the reads
   FakeVehicle car2=new FakeVehicle();PreconditioningService s2=create(car2);binder.watch(true);Handler.drain();adv(15000);
   check(car2.batteryInfoReads==2,"reading: "+car2.infoTimes);
   unbind(s2);int n2=car2.batteryInfoReads;adv(60000);
   check(car2.batteryInfoReads==n2&&s2.stops==1,"unbind stops the reads and the idle service");
   // a read that finishes after onDestroy
   FakeVehicle car3=new FakeVehicle();PreconditioningService s3=create(car3);PreconditioningService.LocalBinder b3=binder;
   long w3=t();b3.watch(true);Handler.drain();
   check(car3.batteryInfoReads==1&&b3.history().size()==1,"first read arrived");
   car3.infoHook=()->{if(car3.batteryInfoReads==2)s3.onDestroy();}; // the service dies while the second read is in the car
   adv(10000);
   check(car3.batteryInfoReads==2,"second read was made");
   adv(120000);
   check(car3.batteryInfoReads==2&&b3.history().size()==1&&b3.battery().info.minimumC==15,"its result was ignored, nothing else ran");
   check(Handler.named(VEH).looper.quit&&Handler.named(BAT).looper.quit,"both threads quit");
   check(Handler.named(VEH).pending()==0&&Handler.named(BAT).pending()==0,"nothing left scheduled");
   done(car);done(car2);done(car3);
  });
  scenario("16 pre-check cache: a 9.9 s old sample stands in; at 10.0 s a fresh read is made",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();car.sample=10;car.minimum=25;PreconditioningService s=create(car);
   long w=t();binder.watch(true);Handler.drain();binder.watch(false);Handler.drain();
   check(car.batteryInfoReads==1,"one reading in the cache, reader off");
   adv(9900);tileStart(s);
   check(car.temperatureReads==0,"no extra read call at Start");
   eq(Arrays.asList(begin(w+9900)),log(car),"Start decided on the cached 10 C, although a fresh read would say warm");
   fresh();FakeVehicle car2=new FakeVehicle();car2.sample=10;car2.minimum=25;PreconditioningService s2=create(car2);
   binder.watch(true);Handler.drain();binder.watch(false);Handler.drain();
   adv(10000);tileStart(s2);
   check(car2.temperatureReads==1,"fresh read at 10.0 s");
   check(car2.log.isEmpty()&&binder.snapshot().state==PreconditioningSnapshot.State.WARM,"the fresh 25 C is warm: notice, no Start");
   done(car);done(car2);
  });

  scenario("18 armed, session Active, READY lost: cancelled once, disarmed, service leaves the foreground; no Start in 30 min of cold samples",false,()->lostSession(true));
  scenario("19 armed, session Active, the setting switched off: cancelled once, disarmed, service leaves the foreground; no Start in 30 min of cold samples",false,()->lostSession(false));
  scenario("20a Stop arriving during the arming Start's pre-check read: no Start, disarmed",false,()->armingStop("pre-check",0));
  scenario("20b Stop arriving at the status read before the arming Start is prepared: no Start, disarmed",false,()->armingStop("read 1",1));
  scenario("20c Stop arriving at the first status read inside the arming Start: no Start, disarmed",false,()->armingStop("read 2",2));
  scenario("20d Stop arriving at the last status read before the arming Start goes out: no Start, disarmed",false,()->armingStop("read 3",3));
  scenario("20e Stop arriving in the tick that decides keep warm's Start: no Start, disarmed",false,()->automaticStop(1));
  scenario("20f Stop arriving at the first status read of keep warm's Start: no Start, disarmed",false,()->automaticStop(2));
  scenario("20g Stop arriving at the last status read before keep warm's Start goes out: no Start, disarmed",false,()->automaticStop(3));
  scenario("21 START_ANYWAY while armed over the warm notice: ignored, nothing sent (honoured once disarmed)",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();car.setTemp(22);PreconditioningService s=create(car);adv(2000);
   long a=t();keepWarm(s,30);
   check(binder.snapshot().state==PreconditioningSnapshot.State.WARM&&kw(s).armed(),"warm notice, armed: "+binder.snapshot().state);
   adv(3000);
   check(binder.snapshot().state==PreconditioningSnapshot.State.WARM,"the notice is still showing: "+binder.snapshot().state);
   send(s,anywayIntent());
   check(car.starts==0&&car.log.isEmpty()&&!requested(s),"START_ANYWAY while armed sent nothing: "+car.log);
   check(kw(s).armed()&&kw(s).why()==KeepWarm.Why.NONE,"still armed: "+kw(s).why());
   adv(60000);
   check(car.log.isEmpty()&&kw(s).armed(),"nothing sent later either: "+car.log);
   // Contrast: the same intent over a warm notice is honoured when keep warm is not armed.
   tileStop(s);
   check(!kw(s).armed()&&car.log.isEmpty(),"disarmed by Stop");
   tileStart(s);
   check(binder.snapshot().state==PreconditioningSnapshot.State.WARM&&car.log.isEmpty(),"warm notice again: "+binder.snapshot().state);
   long b=t();send(s,anywayIntent());
   eq(Arrays.asList(begin(b)),log(car),"disarmed: START_ANYWAY sends the request");
   done(car);
  });
  scenario("22 watch(true), arm, watch(false), watch(true) in quick succession: one reader loop, one read per 10 s over 10 minutes",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);adv(2000);
   long w=t();
   binder.watch(true);Handler.drain();keepWarm(s,30);binder.watch(false);Handler.drain();binder.watch(true);Handler.drain();
   // Each restart reads once at once; all of those are at the instant of the calls.
   int burst=car.infoTimes.size();
   check(burst>=1&&burst<=3,"reads at once: "+car.infoTimes);
   for(long at:car.infoTimes)eq(w,at,"read at once");
   adv(600000);
   List<Long> expected=new ArrayList<>();for(int k=1;k<=60;k++)expected.add(w+10000L*k);
   eq(expected,new ArrayList<>(car.infoTimes.subList(burst,car.infoTimes.size())),"after the restarts, exactly one read every 10 s");
   check(Handler.named(BAT).pending()==1,"one read waiting on the battery thread: "+Handler.named(BAT).pending());
   done(car);
  });
  scenario("22b a restart that arrives while a read is in the car: the old read's result is dropped, still one loop",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);adv(2000);
   long w=t();binder.watch(true);Handler.drain();
   car.infoHook=()->{if(car.batteryInfoReads==2){binder.watch(false);binder.watch(true);}}; // queued for the worker while the read is in the car
   adv(10000);
   List<Long> expected=new ArrayList<>(Arrays.asList(w,w+10000,w+10000));
   eq(expected,car.infoTimes,"the read in flight, then the restart's read at the same instant");
   check(binder.history().size()==1&&binder.history().get(0).at==w+10000,"the restart began a new graph: "+binder.history().size());
   adv(600000);
   for(int k=1;k<=60;k++)expected.add(w+10000+10000L*k);
   eq(expected,car.infoTimes,"afterwards exactly one read every 10 s");
   done(car);
  });
  scenario("23 quietSession: false for the arming Start and a manual Start, true for the whole session keep warm started by itself",false,()->{
   Ctx c=armedIdle(30);long a=c.a;
   check(!binder.quietSession(),"the arming Start is the driver's own");
   c.car.indicator=1;adv(3000);
   check(binder.snapshot().state==PreconditioningSnapshot.State.ACTIVE&&!binder.quietSession(),"arming session Active: not quiet");
   c.car.indicator=0;adv(4000);
   check(!binder.snapshot().requested&&!binder.quietSession(),"arming session over: not quiet");
   startsExactlyAt(c.car,1,a+480000);
   check(binder.snapshot().state==PreconditioningSnapshot.State.WAITING&&binder.quietSession(),"keep warm's own Start, waiting: quiet");
   c.car.indicator=1;adv(5000);
   check(binder.snapshot().state==PreconditioningSnapshot.State.ACTIVE&&binder.quietSession(),"keep warm's own session Active: quiet");
   c.car.indicator=0;adv(2000);
   check(!binder.snapshot().requested&&!binder.quietSession(),"keep warm's session over: not quiet");
   tileStart(c.s);
   check(c.car.starts==3&&binder.snapshot().requested&&!binder.quietSession(),"a manual Start while armed: not quiet");
   c.car.indicator=1;adv(3000);
   check(binder.snapshot().state==PreconditioningSnapshot.State.ACTIVE&&!binder.quietSession(),"manual session Active: not quiet");
   done(c.car);
  });
  scenario("23b a Stop ends the quiet session and the flag with it",false,()->{
   Ctx c=afterFirstSession(30);long a=c.a;
   startsExactlyAt(c.car,1,a+480000);
   check(binder.quietSession(),"quiet while keep warm's session runs");
   tileStop(c.s);
   check(!kw(c.s).armed(),"Stop disarmed");
   c.car.indicator=0;adv(2000);
   check(!binder.snapshot().requested&&!binder.quietSession(),"session over: not quiet");
   done(c.car);
  });
  scenario("24 the graph history is empty after the reader stopped and restarted while not armed",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);
   long w=t();binder.watch(true);Handler.drain();adv(35000);
   check(binder.history().size()==4&&binder.history().get(0).at==w,"first viewing: 4 points from "+w+": "+binder.history().size());
   binder.watch(false);Handler.drain();adv(60000);
   check(car.batteryInfoReads==4&&binder.battery().reader==BatteryStatus.Reader.IDLE,"reader stopped");
   car.sample=null; // the first read after the restart fails, so the graph can hold nothing but what is new
   long w2=t();binder.watch(true);Handler.drain();
   check(binder.history().isEmpty(),"restarted: the old graph is gone, but it has "+binder.history().size()+" points");
   car.sample=15;adv(10000);
   check(binder.history().size()==1&&binder.history().get(0).at==w2+10000&&binder.history().get(0).minimumC==15,"only the new reading: "+binder.history().size());
   done(car);
  });
  scenario("24b while keep warm is armed the graph survives the monitor closing and opening",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);adv(2000);
   long a=t();keepWarm(s,30);binder.watch(true);Handler.drain();adv(65000);
   int n=binder.history().size();
   check(n>=6&&binder.history().get(0).at==a,"graph since arming: "+n+" points");
   binder.watch(false);Handler.drain();binder.watch(true);Handler.drain();
   check(binder.history().size()>=n&&binder.history().get(0).at==a,"still the same graph: "+binder.history().size()+" points");
   done(car);
  });

  scenario("25a rebind as on Android 4.4: watch(true) arrives before onRebind; reads resume at onRebind",false,()->rebindOrder(true));
  scenario("25b rebind in the other order: onRebind, then watch(true); reads start at watch(true)",false,()->rebindOrder(false));
  scenario("26 five failed reads stop the reader; a second watch(true) restarts it, without it nothing is read",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();car.sample=null;PreconditioningService s=create(car);adv(2000);
   long w=t();binder.watch(true);Handler.drain();adv(45000);
   eq(Arrays.asList(w,w+10000,w+20000,w+30000,w+40000),car.infoTimes,"five reads, all failing");
   check(binder.battery().reader==BatteryStatus.Reader.STOPPED&&binder.battery().info==null,"reader stopped: "+binder.battery().reader);
   adv(300000);
   check(car.batteryInfoReads==5&&binder.battery().reader==BatteryStatus.Reader.STOPPED,"without a new watch(true) it stays stopped: "+car.batteryInfoReads);
   car.sample=15;long w2=t();
   binder.watch(true);Handler.drain();
   check(car.batteryInfoReads==6&&car.infoTimes.get(5)==w2&&binder.battery().reader==BatteryStatus.Reader.READING&&binder.battery().info!=null,"restarted with a read at once: "+car.infoTimes);
   adv(20000);
   check(car.batteryInfoReads==8&&car.infoTimes.get(7)==w2+20000,"and one every 10 s: "+car.infoTimes);
   // Asking again while it reads normally restarts nothing.
   adv(3000);binder.watch(true);Handler.drain();
   check(car.batteryInfoReads==8,"no extra read when the reader is healthy: "+car.batteryInfoReads);
   done(car);
  });
  scenario("27a keep warm's Start fails before anything is sent (journal write): disarmed at once, CAR_ERROR, no Start later",false,()->journalFailure(false));
  scenario("27b the arming Start fails before anything is sent (journal write): not left armed, no Start later",false,()->journalFailure(true));
  scenario("28a no reading in the 45 s before Start, the first one 2 minutes into the session: no start temperature",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);adv(2000);
   tileStart(s);long b=t();
   check(requested(s)&&binder.battery().startC==null,"no reading yet");
   adv(120000);binder.watch(true);Handler.drain();
   check(car.batteryInfoReads==1&&binder.battery().info!=null&&binder.battery().info.minimumC==15,"the reading came 2 minutes in");
   check(binder.battery().startC==null,"too late to be the start: "+binder.battery().startC);
   car.sample=11;adv(20000);
   check(binder.battery().startC==null&&binder.battery().info.minimumC==11,"and later ones do not become it: "+binder.battery().startC);
   done(car);
  });
  scenario("28b the first reading arrives 30 s into the session: that is the start temperature, and it stays",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);adv(2000);
   tileStart(s);car.sample=13;
   adv(30000);binder.watch(true);Handler.drain();
   check(binder.battery().startC!=null&&binder.battery().startC==13,"start temperature: "+binder.battery().startC);
   car.sample=17;adv(30000);
   check(binder.battery().info.minimumC==17&&binder.battery().startC==13,"stays at the first reading: "+binder.battery().startC);
   car.indicator=1;adv(2000);car.indicator=0;adv(3000);
   check(!requested(s)&&binder.battery().startC==null,"no session, no start temperature: "+binder.battery().startC);
   done(car);
  });
  scenario("28c a fresh reading just before Start is the start temperature",false,()->{
   fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);adv(2000);
   car.sample=12;binder.watch(true);Handler.drain();adv(5000);
   tileStart(s);
   check(requested(s)&&binder.battery().startC!=null&&binder.battery().startC==12,"start temperature: "+binder.battery().startC);
   car.sample=19;adv(30000);
   check(binder.battery().info.minimumC==19&&binder.battery().startC==12,"stays: "+binder.battery().startC);
   done(car);
  });
  scenario("28d a reading 45.0 s old at Start counts, one of 45.5 s does not",false,()->{
   for(long age:new long[]{45000,45500}){
    fresh();FakeVehicle car=new FakeVehicle();PreconditioningService s=create(car);adv(2000);
    car.sample=12;binder.watch(true);Handler.drain();binder.watch(false);Handler.drain();
    adv(age);
    tileStart(s);
    check(requested(s),"session started");
    check(age==45000?(binder.battery().startC!=null&&binder.battery().startC==12):binder.battery().startC==null,"reading "+age+" ms old: "+binder.battery().startC);
    done(car);
   }
  });

  System.out.println(passed+" service lifecycle/precheck/keep-warm scenarios passed, "+failed+" failed (host fixtures, no car access)");
  if(failed>0)System.exit(1);
 }
 static FakeVehicle car1; static PreconditioningService s1; static long start1;
 static void advTo(long at){long d=at-t();check(d>=0,"clock already past +"+(-d));if(d>0)adv(d);}
}''', encoding='utf-8')
production = ROOT/'app/src/main/java/com/tilepad'
names = ('PreconditioningService', 'PreconditioningController', 'PreconditioningSnapshot', 'VehiclePort',
         'BatteryTemperature', 'PreconditioningProfile', 'KeepWarm', 'TemperatureLog', 'EventTrail', 'BatteryStatus')
sources = [str(p) for p in (work/'src').rglob('*.java')] + [str(production/f'{n}.java') for n in names]
classes = work/'classes'; classes.mkdir()
subprocess.run([JAVAC, '-d', str(classes), *sources], check=True, timeout=60)
result = subprocess.run([JAVA, '-cp', str(classes), 'com.tilepad.ServiceRebindTest'],
                        capture_output=True, text=True, timeout=300)
(work/'results.json').write_text(json.dumps({'passed': result.returncode == 0,
    'scope': 'host Android fixtures, no car access', 'output': result.stdout, 'errors': result.stderr}, indent=2) + '\n')
print(result.stdout)
if result.stderr:
    print(result.stderr)
raise SystemExit(result.returncode)
