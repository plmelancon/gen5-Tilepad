"""Compile the real reader against small Binder contract fixtures; no car access."""
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
work = ROOT/'build/precon-temperature-contract'
(work/'android/os').mkdir(parents=True, exist_ok=True)
stubs = {
 'RemoteException': 'public class RemoteException extends Exception { public RemoteException(String s){super(s);} }',
 'IBinder': '''public interface IBinder { int INTERFACE_TRANSACTION=1598968902;
 boolean transact(int c,Parcel d,Parcel r,int f)throws RemoteException;
 String getInterfaceDescriptor()throws RemoteException; boolean isBinderAlive(); }''',
 'Binder': '''public class Binder implements IBinder {
 public void attachInterface(Object o,String s){} public String getInterfaceDescriptor(){return "";}
 public boolean isBinderAlive(){return true;}
 public boolean transact(int c,Parcel d,Parcel r,int f)throws RemoteException{return onTransact(c,d,r,f);}
 protected boolean onTransact(int c,Parcel d,Parcel r,int f)throws RemoteException{return false;} }''',
 'ServiceManager': '''public class ServiceManager { public static IBinder current;
 public static IBinder getService(String n){if(!n.equals("com.hkmc.micom.cluster"))throw new AssertionError(n);return current;} }''',
 'Parcel': '''public class Parcel { public String token;public int[] array;public IBinder binder;public boolean ok;public int extra;
 public static Parcel obtain(){return new Parcel();} public void recycle(){}
 public void writeInterfaceToken(String s){token=s;} public void enforceInterface(String s){if(!s.equals(token))throw new SecurityException();}
 public void writeStrongBinder(IBinder b){binder=b;}public void writeString(String s){}
 public int[] createIntArray(){return array;}public int dataAvail(){return extra;}
 public void writeNoException(){ok=true;}public void readException(){if(!ok)throw new SecurityException("remote failure");} }''',
}
for name, body in stubs.items():
    (work/'android/os'/f'{name}.java').write_text('package android.os;\n'+body)
test = work/'com/tilepad/TemperatureBinderTest.java'
test.parent.mkdir(parents=True, exist_ok=True)
test.write_text('''package com.tilepad;
import android.os.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
public class TemperatureBinderTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static final String TOKEN="com.hkmc.cluster.INavigationClusterListener";
 static class Remote extends Binder {
  List<Integer> calls=Collections.synchronizedList(new ArrayList<Integer>()); IBinder listener; int mode;
  int[] reply; int trailing; // reply: whole 8-integer answer to transaction 109; trailing: unread data left in the callback parcel
  final AtomicInteger registrations=new AtomicInteger(),removals=new AtomicInteger();
  final Set<IBinder> registered=Collections.synchronizedSet(new HashSet<IBinder>());
  CountDownLatch release=new CountDownLatch(1),finished=new CountDownLatch(1),entered=new CountDownLatch(1);
  Remote(int mode){this.mode=mode;}
  public String getInterfaceDescriptor(){return mode==6?"wrong":"com.hkmc.cluster.IClusterService";}
  public boolean isBinderAlive(){return mode!=7;}
  void response(int temp,boolean malformed,int[] whole)throws RemoteException{
   Parcel d=Parcel.obtain(),r=Parcel.obtain();d.token=TOKEN;
   d.array=malformed?new int[7]:whole!=null?whole:new int[]{96,temp,16620,0,113,0,202,0};
   if(whole!=null)d.extra=trailing;
   check(listener.transact(16,d,r,0));check(r.ok);
  }
  public boolean transact(int code,Parcel d,Parcel r,int flags)throws RemoteException{
   check(flags==0 && d.token.equals("com.hkmc.cluster.IClusterService")); calls.add(code);
   check(code==37||code==38||code==109);
   if(code==37){listener=d.binder;check(listener!=null);registrations.incrementAndGet();registered.add(listener);
    if(mode==3)throw new RemoteException("registration failed");
    response(60,false,null); // Must ignore unsolicited callback before explicit request.
   }
   if(code==109){check(d.binder==null);
    if(mode==4)throw new RemoteException("request failed");
    if(mode==8){entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}}
    if(mode!=2)response(20,mode==1,reply);
   }
   if(code==38){check(d.binder==listener);removals.incrementAndGet();finished.countDown();
    if(mode==5)throw new RemoteException("remove failed");
    registered.remove(d.binder);}
   r.writeNoException(); return true;
  }
 }
 interface Body{void run()throws Exception;}
 static int passed,failed;
 static void scenario(String name,boolean suspectedBug,Body b){
  try{b.run();passed++;System.out.println("PASS "+name);}
  catch(Throwable t){failed++;System.out.println((suspectedBug?"SUSPECTED BUG - FAIL ":"FAIL ")+name+": "+t);}
 }
 static BatteryTemperature.Info readInfo(){return ClusterBatteryTemperatureReader.readInfo();}
 static int liveHelpers(){int n=0;for(Thread t:Thread.getAllStackTraces().keySet())
  if(t.isAlive()&&t.getName().equals("tilepad-battery-precheck"))n++;return n;}
 static void waitUntilNotStuck()throws Exception{
  long end=System.nanoTime()+2000000000L;
  while(ClusterBatteryTemperatureReader.stuck()&&System.nanoTime()<end)Thread.sleep(5);
 }
 // Two callers (one read(), one readInfo()) at once on a request held open: they share it (one registration).
 static void sharedRequest(boolean readFirst)throws Exception{
  Remote shared=new Remote(8);ServiceManager.current=shared;
  Integer[] value=new Integer[1];BatteryTemperature.Info[] info=new BatteryTemperature.Info[1];
  Runnable viaRead=()->value[0]=ClusterBatteryTemperatureReader.read();
  Runnable viaInfo=()->info[0]=ClusterBatteryTemperatureReader.readInfo();
  Thread first=new Thread(readFirst?viaRead:viaInfo),second=new Thread(readFirst?viaInfo:viaRead);
  first.start();shared.entered.await();second.start();
  long deadline=System.nanoTime()+500000000L;
  while(second.getState()!=Thread.State.TIMED_WAITING && System.nanoTime()<deadline)Thread.sleep(1);
  check(second.getState()==Thread.State.TIMED_WAITING,"second caller waits on the first request");
  shared.release.countDown();first.join(1000);second.join(1000);
  check(!first.isAlive()&&!second.isAlive());
  check(info[0]!=null&&info[0].minimumC==20&&Integer.valueOf(20).equals(value[0]),"both callers got the one answer");
  check(shared.calls.equals(Arrays.asList(37,109,38)),"one in-flight request: "+shared.calls);
  check(shared.registrations.get()==1&&shared.removals.get()==1,"exactly one registration");
 }
 public static void main(String[] args)throws Exception {
  // ---- the ten original scenarios (same meaning as before the readInfo change) ----
  for(int mode=0;mode<8;mode++){
   final int m=mode;
   scenario("original "+(mode+1)+": read() mode "+mode+(mode==5?" (removal fails: read() is null; see the removal-failure scenario below)":""),false,()->{
    Remote remote=new Remote(m);ServiceManager.current=remote;
    Integer value=ClusterBatteryTemperatureReader.read();
    check(m==0?Integer.valueOf(20).equals(value):value==null);
    if(m>=6)check(remote.calls.isEmpty());
    else check(remote.calls.equals(m==3?Arrays.asList(37,38):Arrays.asList(37,109,38)));
   });
  }
  scenario("original 9: hung transaction times out, no second helper, late value never reused",false,()->{
   Remote slow=new Remote(8);ServiceManager.current=slow;
   long before=System.nanoTime();check(ClusterBatteryTemperatureReader.read()==null);
   long ms=(System.nanoTime()-before)/1000000;check(ms<2600);
   check(ClusterBatteryTemperatureReader.read()==null); // No second hung helper.
   check(slow.calls.equals(Arrays.asList(37,109)));
   slow.release.countDown();slow.finished.await();Thread.sleep(50);
   Remote next=new Remote(1);ServiceManager.current=next;
   check(ClusterBatteryTemperatureReader.read()==null); // Never reuse late20C from old request.
   check(next.calls.equals(Arrays.asList(37,109,38)));
  });
  scenario("original 10: two read() callers share one in-flight request",false,()->{
   Remote shared=new Remote(8);ServiceManager.current=shared;
   Integer[] values=new Integer[2];
   Thread first=new Thread(()->values[0]=ClusterBatteryTemperatureReader.read());
   Thread second=new Thread(()->values[1]=ClusterBatteryTemperatureReader.read());
   first.start();shared.entered.await();second.start();
   long deadline=System.nanoTime()+500000000L;
   while(second.getState()!=Thread.State.TIMED_WAITING && System.nanoTime()<deadline)Thread.sleep(1);
   check(second.getState()==Thread.State.TIMED_WAITING);
   shared.release.countDown();first.join(1000);second.join(1000);
   check(!first.isAlive()&&!second.isAlive());
   check(Integer.valueOf(20).equals(values[0])&&Integer.valueOf(20).equals(values[1]));
   check(shared.calls.equals(Arrays.asList(37,109,38)));
  });

  // ---- new: readInfo() ----
  scenario("readInfo: all four fields of a valid reply",false,()->{
   Remote r=new Remote(0);r.reply=new int[]{96,14,16620,0,113,5,202,0};ServiceManager.current=r;
   BatteryTemperature.Info info=readInfo();
   check(info!=null,"valid reply decoded");
   check(info.minimumC==14,"minimumC is index 1: "+info.minimumC);
   check(info.chargeRaw==96,"chargeRaw is index 0: "+info.chargeRaw);
   check(info.outsideRaw==113,"outsideRaw is index 4: "+info.outsideRaw);
   check(info.conditioningRaw==5,"conditioningRaw is index 5: "+info.conditioningRaw);
   check(r.calls.equals(Arrays.asList(37,109,38)));
   Remote cold=new Remote(0);cold.reply=new int[]{200,-40,1,2,-7,9,3,4};ServiceManager.current=cold;
   info=readInfo();check(info!=null&&info.minimumC==-40&&info.chargeRaw==200&&info.outsideRaw==-7&&info.conditioningRaw==9);
   // read() is the wrapper over readInfo().minimumC.
   Remote wrapped=new Remote(0);wrapped.reply=new int[]{0,60,1,2,3,4,5,6};ServiceManager.current=wrapped;
   check(Integer.valueOf(60).equals(ClusterBatteryTemperatureReader.read()));
  });
  for(int mode=1;mode<8;mode++){
   final int m=mode;
   scenario("readInfo: null for the malformed case of mode "+mode,false,()->{
    Remote remote=new Remote(m);ServiceManager.current=remote;
    check(readInfo()==null);
    if(m>=6)check(remote.calls.isEmpty());
    else check(remote.calls.equals(m==3?Arrays.asList(37,38):Arrays.asList(37,109,38)));
   });
  }
  scenario("readInfo: null for implausible values and for unread data in the callback",false,()->{
   int[][] bad={{201,20,0,0,0,0,0,0},{-1,20,0,0,0,0,0,0},{96,61,0,0,0,0,0,0},{96,-41,0,0,0,0,0,0},{96,20,0,0,0,0,0,0,0}};
   for(int[] reply:bad){Remote r=new Remote(0);r.reply=reply;ServiceManager.current=r;
    check(readInfo()==null,"rejected "+Arrays.toString(reply));check(r.calls.equals(Arrays.asList(37,109,38)));}
   Remote extra=new Remote(0);extra.reply=new int[]{96,20,16620,0,113,0,202,0};extra.trailing=4;ServiceManager.current=extra;
   check(readInfo()==null,"callback parcel with unread data");
   Remote ok=new Remote(0);ServiceManager.current=ok;check(readInfo()!=null); // and the next read is fine
  });

  // ---- new: stuck() ----
  scenario("stuck(): false normally, true after a hung call passes its deadline, no second helper, false again after it returns",false,()->{
   check(!ClusterBatteryTemperatureReader.stuck(),"false before any read of this scenario");
   Remote ok=new Remote(0);ServiceManager.current=ok;
   check(readInfo()!=null);check(!ClusterBatteryTemperatureReader.stuck(),"false after a successful read");
   Remote bad=new Remote(1);ServiceManager.current=bad;check(readInfo()==null);
   check(!ClusterBatteryTemperatureReader.stuck(),"false after a read that failed but finished");
   Remote hung=new Remote(8);ServiceManager.current=hung;
   BatteryTemperature.Info[] out=new BatteryTemperature.Info[]{new BatteryTemperature.Info(0,0,0,0)};
   Thread caller=new Thread(()->out[0]=readInfo());caller.start();
   hung.entered.await();
   check(!ClusterBatteryTemperatureReader.stuck(),"still inside its 1.8 s: not stuck yet");
   long before=System.nanoTime();caller.join(5000);
   check(!caller.isAlive()&&out[0]==null,"the caller gave up at the deadline");
   check((System.nanoTime()-before)/1000000<2600);
   check(ClusterBatteryTemperatureReader.stuck(),"true after the deadline while the helper is blocked");
   check(readInfo()==null&&ClusterBatteryTemperatureReader.read()==null,"no answer while stuck");
   check(ClusterBatteryTemperatureReader.stuck(),"stays true");
   check(hung.calls.equals(Arrays.asList(37,109)),"no second helper was started: "+hung.calls);
   check(hung.registrations.get()==1);
   hung.release.countDown();hung.finished.await();waitUntilNotStuck();
   check(!ClusterBatteryTemperatureReader.stuck(),"false again once the hung call returned");
   Remote late=new Remote(1);ServiceManager.current=late;
   check(readInfo()==null,"the late answer of the old request is never reused");
   Remote next=new Remote(0);ServiceManager.current=next;
   check(readInfo()!=null&&!ClusterBatteryTemperatureReader.stuck());
  });

  // ---- new: soak ----
  scenario("soak: 3000 consecutive readInfo() calls leak no registration and no thread",false,()->{
   Remote soak=new Remote(0);soak.reply=new int[]{96,17,16620,0,113,3,202,0};ServiceManager.current=soak;
   for(int i=0;i<3000;i++){
    BatteryTemperature.Info v=readInfo();
    check(v!=null&&v.minimumC==17&&v.conditioningRaw==3,"read "+i);
   }
   check(soak.registrations.get()==3000,"registrations "+soak.registrations.get());
   check(soak.removals.get()==3000,"removals "+soak.removals.get());
   check(soak.registered.isEmpty(),"none left registered: "+soak.registered.size());
   check(soak.calls.size()==9000);
   int live=liveHelpers();check(live==0||live==1,"live tilepad-battery-precheck threads: "+live);
   check(!ClusterBatteryTemperatureReader.stuck());
  });

  // ---- new: removal (transaction 38) failing ----
  // A failed removal leaves the registration uncertain, so the read reports nothing (scenario 6).
  scenario("removal failure: the next read works",false,()->{
   Remote r=new Remote(5);ServiceManager.current=r;
   ClusterBatteryTemperatureReader.read();
   check(r.calls.equals(Arrays.asList(37,109,38)));
   r.mode=0; // the car recovers; same service object
   BatteryTemperature.Info next=readInfo();
   check(next!=null&&next.minimumC==20,"next read works");
   check(r.calls.equals(Arrays.asList(37,109,38,37,109,38)),"next read ran its full exchange: "+r.calls);
   Remote other=new Remote(0);ServiceManager.current=other;
   check(Integer.valueOf(20).equals(ClusterBatteryTemperatureReader.read()));
  });

  // ---- new: read() and readInfo() at the same time ----
  scenario("read() and readInfo() at once share one in-flight request",false,()->sharedRequest(true));
  scenario("readInfo() and read() at once share one in-flight request",false,()->sharedRequest(false));

  System.out.println(passed+" Binder contract scenarios passed, "+failed+" failed (host fixtures, no car access)");
  if(failed>0)System.exit(1);
 }
}''', encoding='utf-8')
production = ROOT/'app/src/main/java/com/tilepad'
sources = [str(p) for p in work.rglob('*.java')]+[str(production/n) for n in ('BatteryTemperature.java','ClusterBatteryTemperatureReader.java')]
classes=work/'classes';classes.mkdir(exist_ok=True)
subprocess.run([JAVAC,'-d',str(classes),*sources],check=True,timeout=60)
result=subprocess.run([JAVA,'-cp',str(classes),'com.tilepad.TemperatureBinderTest'],capture_output=True,text=True,timeout=120)
(work/'results.json').write_text(json.dumps({'passed':result.returncode==0,'scope':'host Binder fixtures, no car access','output':result.stdout,'errors':result.stderr},indent=2)+'\n')
print(result.stdout)
if result.stderr:
    print(result.stderr)
raise SystemExit(result.returncode)
