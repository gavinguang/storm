package backtype.storm.utils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Expires keys that have not been updated in the configured number of seconds.
 * The algorithm used will take between expirationSecs and
 * expirationSecs * (1 + 1 / (numBuckets-1)) to actually expire the message.
 *
 * get, put, remove, containsKey, and size take O(numBuckets) time to run.
 *
 * The advantage of this design is that the expiration thread only locks the object
 * for O(1) time, meaning the object is essentially(本质上) always available for gets/puts.
 */
//deprecated in favor of non-threaded RotatingMap
@Deprecated
public class TimeCacheMap<K, V> {
    //this default ensures things expire at most 50% past the expiration time
    private static final int DEFAULT_NUM_BUCKETS = 3;

    public static interface ExpiredCallback<K, V> {
        public void expire(K key, V val);
    }

    //数据保存在这里面.
    private LinkedList<HashMap<K, V>> _buckets;

    private final Object _lock = new Object();
    private Thread _cleaner;
    private ExpiredCallback _callback;
    
    public TimeCacheMap(int expirationSecs, int numBuckets, ExpiredCallback<K, V> callback) {
        if(numBuckets<2) {
            throw new IllegalArgumentException("numBuckets must be >= 2");
        }
	//生成numBukets个空的HashMap
        _buckets = new LinkedList<HashMap<K, V>>();
        for(int i=0; i<numBuckets; i++) {
            _buckets.add(new HashMap<K, V>());
        }


        _callback = callback;
        final long expirationMillis = expirationSecs * 1000L;
	//为什么是 expirationMillis / (numBuckets-1) ?
	//TimeCacheMap内部有多个同,当向里面添加数据的时候,数据总是添加到第一个桶里面去的.
        final long sleepTime = expirationMillis / (numBuckets-1);
	//使用单独的线程清理过期的数据. 
        _cleaner = new Thread(new Runnable() {
            public void run() {
                try {
                    while(true) {
                        Map<K, V> dead = null;
                        Time.sleep(sleepTime);
                        synchronized(_lock) {
			   //每隔sleepTime时间把最后一个bucket的数据全部删除掉.
			   //正式因为分成多个桶的机制,清理线程对于_lock 的占用时间极短.只是把最后一个bucket从_buckets解下,并且想头上面添加一个新的bucket就好了.
                            dead = _buckets.removeLast();
                            _buckets.addFirst(new HashMap<K, V>());
                        }
                        if(_callback!=null) {
				//对于过期数据执行callback函数.
                            for(Entry<K, V> entry: dead.entrySet()) {
                                _callback.expire(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                } catch (InterruptedException ex) {

                }
            }
        });
        _cleaner.setDaemon(true);
        _cleaner.start();
    }

    public TimeCacheMap(int expirationSecs, ExpiredCallback<K, V> callback) {
        this(expirationSecs, DEFAULT_NUM_BUCKETS, callback);
    }

    public TimeCacheMap(int expirationSecs) {
        this(expirationSecs, DEFAULT_NUM_BUCKETS);
    }

    public TimeCacheMap(int expirationSecs, int numBuckets) {
        this(expirationSecs, numBuckets, null);
    }


    public boolean containsKey(K key) {
        synchronized(_lock) {
            for(HashMap<K, V> bucket: _buckets) {
                if(bucket.containsKey(key)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V get(K key) {
        synchronized(_lock) {
            for(HashMap<K, V> bucket: _buckets) {
                if(bucket.containsKey(key)) {
                    return bucket.get(key);
                }
            }
            return null;
        }
    }

    public void put(K key, V value) {
        synchronized(_lock) {
            Iterator<HashMap<K, V>> it = _buckets.iterator();
	    //向第一个桶中添加数据
            HashMap<K, V> bucket = it.next();
            bucket.put(key, value);
	    //并删除其他桶中存在相同的key的数据.
            while(it.hasNext()) {
                bucket = it.next();
                bucket.remove(key);
            }
        }
    }
    
    public Object remove(K key) {
        synchronized(_lock) {
            for(HashMap<K, V> bucket: _buckets) {
                if(bucket.containsKey(key)) {
                    return bucket.remove(key);
                }
            }
            return null;
        }
    }

    public int size() {
        synchronized(_lock) {
            int size = 0;
            for(HashMap<K, V> bucket: _buckets) {
                size+=bucket.size();
            }
            return size;
        }
    }

    public void cleanup() {
        _cleaner.interrupt();
    }    
}
