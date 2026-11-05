package app.organicmaps.sdk.transit;

import android.content.SharedPreferences;
import java.util.*;

/** Native-fixture Config facade only: never opens a preferences file or owner Context. */
public final class FixtureMemoryPreferences implements SharedPreferences {
  private final Map<String,Object> values=new HashMap<>();
  private final Set<OnSharedPreferenceChangeListener> listeners=new HashSet<>();
  @Override public synchronized Map<String,?> getAll(){return new HashMap<>(values);}
  @Override public synchronized boolean contains(String k){return values.containsKey(k);}
  @Override public synchronized String getString(String k,String d){return (String)values.getOrDefault(k,d);}
  @Override public synchronized Set<String> getStringSet(String k,Set<String> d){Object v=values.get(k);return v==null?d:new HashSet<>((Set<String>)v);}
  @Override public synchronized int getInt(String k,int d){return (Integer)values.getOrDefault(k,d);}
  @Override public synchronized long getLong(String k,long d){return (Long)values.getOrDefault(k,d);}
  @Override public synchronized float getFloat(String k,float d){return (Float)values.getOrDefault(k,d);}
  @Override public synchronized boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}
  @Override public synchronized void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){listeners.add(l);}
  @Override public synchronized void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){listeners.remove(l);}
  @Override public Editor edit(){return new Editor(){
    final Map<String,Object> changed=new HashMap<>();boolean clear;
    private Editor put(String k,Object v){changed.put(k,v);return this;}
    public Editor putString(String k,String v){return put(k,v);}
    public Editor putStringSet(String k,Set<String> v){return put(k,v==null?null:new HashSet<>(v));}
    public Editor putInt(String k,int v){return put(k,v);}
    public Editor putLong(String k,long v){return put(k,v);}
    public Editor putFloat(String k,float v){return put(k,v);}
    public Editor putBoolean(String k,boolean v){return put(k,v);}
    public Editor remove(String k){return put(k,null);}
    public Editor clear(){clear=true;return this;}
    public boolean commit(){apply();return true;}
    public void apply(){Set<String> keys;Set<OnSharedPreferenceChangeListener> notify;
      synchronized(FixtureMemoryPreferences.this){keys=new HashSet<>(changed.keySet());if(clear){keys.addAll(values.keySet());values.clear();}for(var e:changed.entrySet())if(e.getValue()==null)values.remove(e.getKey());else values.put(e.getKey(),e.getValue());notify=new HashSet<>(listeners);}
      for(String k:keys)for(var l:notify)l.onSharedPreferenceChanged(FixtureMemoryPreferences.this,k);
    }
  };}
}
