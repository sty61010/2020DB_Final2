package org.vanilladb.calvin.cache;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.vanilladb.calvin.sql.PrimaryKey;
import org.vanilladb.core.server.task.Task;
import org.vanilladb.core.storage.tx.Transaction;

public class CacheMgr extends Task {
	
	private static class HoldPackage {
		long txNum;
		InMemoryRecord record;
		
		HoldPackage(long txNum, InMemoryRecord record) {
			this.txNum = txNum;
			this.record = record;
		}
	}
	// cached record test
	private Map<PrimaryKey, InMemoryRecord> hotRecord = new ConcurrentHashMap<PrimaryKey, InMemoryRecord>();
	private Map<PrimaryKey, InMemoryRecord> coldRecord = new ConcurrentHashMap<PrimaryKey, InMemoryRecord>(200000);
	
	private Map<Long, TransactionCache> caches = new ConcurrentHashMap<Long, TransactionCache>();
	private LinkedBlockingQueue<HoldPackage> newPacks = new LinkedBlockingQueue<HoldPackage>();
	
	// cached record test
	public InMemoryRecord getCachedRecord(PrimaryKey key) {
		if(key.getTableName().equals("warehouse") || key.getTableName().equals("district") )
			return hotRecord.get(key);
		else return coldRecord.get(key);
	}
	
	public void cacheRecord(PrimaryKey key, InMemoryRecord rec, Transaction tx) {
		if(key.getTableName().equals("warehouse") || key.getTableName().equals("district"))
			hotRecord.put(key, rec);
		else {
			if(coldRecord.size() >= 190000 && coldRecord.get(key)==null)
				replace(key, rec, tx);
			else {
				if(coldRecord.get(key) != null && coldRecord.get(key).isNewInserted()) {
					rec.setToNewInserted();
				}
				coldRecord.put(key, rec);
			}
		}
	}
	
	public void delete(PrimaryKey key, Transaction tx) {
		if(key.getTableName().equals("warehouse") || key.getTableName().equals("district"))
			hotRecord.remove(key);
		else coldRecord.remove(key);
		
		VanillaCoreStorage.delete(key, tx);
	}
	
	private void replace(PrimaryKey key, InMemoryRecord rec, Transaction tx) {
		//ArrayList<PrimaryKey> removeSet = new ArrayList<PrimaryKey>();
		/*int i = 0;
		for(Map.Entry<PrimaryKey, InMemoryRecord> entry: coldRecord.entrySet()) {
			i++;
			InMemoryRecord rec = entry.getValue();
			PrimaryKey key = entry.getKey();
			if (rec.isNewInserted())
				VanillaCoreStorage.insert(key, rec, tx);
			else if (rec.isDirty())
				VanillaCoreStorage.update(key, rec, tx);
			coldRecord.remove(key);
			
			if(i>0) break;
		}*/
		if (rec.isNewInserted())
			VanillaCoreStorage.insert(key, rec, tx);
		else if (rec.isDirty())
			VanillaCoreStorage.update(key, rec, tx);
	}
	
	public TransactionCache createCache(Transaction tx) {
		TransactionCache cache = new TransactionCache(tx);
		caches.put(tx.getTransactionNumber(), cache);
		return cache;
	}
	
	public void removeCache(long txNum) {
		caches.remove(txNum);
	}
	
	public void cacheRemoteRecord(long txNum, InMemoryRecord record) {
		if (!handoverToTransaction(txNum, record)) {
			HoldPackage pack = new HoldPackage(txNum, record);
			newPacks.add(pack);
		}
	}

	@Override
	public void run() {
		Queue<HoldPackage> pending = new ArrayDeque<HoldPackage>();
		while (true) {
			try {
				// Check every 1 second (fast enough?)
				HoldPackage pack = newPacks.poll(1, TimeUnit.MILLISECONDS);
				if (pack != null && !handoverToTransaction(pack.txNum, pack.record)) {
					pending.add(pack);
				}
				
				// Handle pending packages
				int count = pending.size();
				for (int i = 0; i < count; i++) {
					pack = pending.poll();
					if (!handoverToTransaction(pack.txNum, pack.record)) {
						pending.add(pack);
					}
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	
	private boolean handoverToTransaction(long txNum, InMemoryRecord record) {
		TransactionCache cache = caches.get(txNum);
		if (cache != null) {
			cache.onReceivedRecord(record);
			return true;
		}
		return false;
	}
}
