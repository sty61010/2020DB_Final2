package org.vanilladb.calvin.schedule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.vanilladb.calvin.cache.InMemoryRecord;
import org.vanilladb.calvin.procedure.CalvinStoredProcedure;
import org.vanilladb.calvin.procedure.CalvinStoredProcedureFactory;
import org.vanilladb.calvin.procedure.CalvinStoredProcedureTask;
import org.vanilladb.calvin.remote.groupcomm.StoredProcedureCall;
import org.vanilladb.calvin.sql.PrimaryKey;
import org.vanilladb.calvin.storage.tx.recovery.CalvinRecoveryMgr;
import org.vanilladb.core.server.VanillaDb;
import org.vanilladb.core.server.task.Task;
import org.vanilladb.core.sql.Constant;

public class Scheduler extends Task {
	private static Logger logger = Logger.getLogger(Scheduler.class.getName());
	
	private CalvinStoredProcedureFactory factory;
	private BlockingQueue<StoredProcedureCall> spcQueue = new LinkedBlockingQueue<StoredProcedureCall>();
	private Graph G = null;
	BlockingQueue<StoredProcedureCall> copySpcQueue = new LinkedBlockingQueue<StoredProcedureCall>();
	List<Vertex> vlist = new ArrayList<Vertex>();

	public Scheduler(CalvinStoredProcedureFactory factory) {
		this.factory = factory;
	}

	synchronized public void schedule(StoredProcedureCall call) {
		try {
			spcQueue.put(call);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	

	@Override
	public void run() {
		StoredProcedureCall call = null;
		try {
			while (true) {
				
				if(spcQueue.isEmpty()) {
					continue;
				}
				
				copySpcQueue.clear();
				synchronized (this) {
					int i=0;
					StoredProcedureCall sp;
					while (i<= 10) {
						sp = spcQueue.poll();
						if (sp == null) break;
						copySpcQueue.add(sp);
						i++;
					}
				}
				
				vlist.clear();
				while (true) {
					call = copySpcQueue.poll();
					if (call == null) break;
					CalvinStoredProcedure<?> sp = factory.getStoredProcedure(
							call.getPid(), call.getTxNum());
					sp.prepare(call.getPars());
					// log request
					if (!sp.isReadOnly()) {
						CalvinRecoveryMgr recoveryMgr = new CalvinRecoveryMgr();
						recoveryMgr.logRequest(call);
					}
					// if this node doesn't have to participate this transaction,
					// skip it
					if (!sp.isParticipating()) {
						continue;
					}
					Vertex v = new Vertex(sp, call.getClientId(), call.getConnectionId(), call.getTxNum());
					vlist.add(v);
				}
				
				G = new Graph(vlist);
				
				while (true) {
					Vertex v = G.getVertex();
					if (v == null) break;
					// serialize conservative locking
					v.sp.bookConservativeLocks();
		
					// create a new task for multi-thread
					CalvinStoredProcedureTask spt = new CalvinStoredProcedureTask(
							v.clientId, v.connectionNum, v.txNum,
							v.sp);
		
					// hand over to a thread to run the task
					VanillaDb.taskMgr().runTask(spt);
				}
				
			}

		} catch (Exception e) {
			if (logger.isLoggable(Level.SEVERE))
				logger.severe("detect Exception in the scheduler, current sp call: " + call);
			e.printStackTrace();
		}
	}
}

////retrieve stored procedure call
//call = spcQueue.take();
//
//// create store procedure and prepare
//CalvinStoredProcedure<?> sp = factory.getStoredProcedure(
//		call.getPid(), call.getTxNum());
//
//sp.prepare(call.getPars());
//
//// log request
//if (!sp.isReadOnly()) {
//	CalvinRecoveryMgr recoveryMgr = new CalvinRecoveryMgr();
//	recoveryMgr.logRequest(call);
//}
//
//// if this node doesn't have to participate this transaction,
//// skip it
//if (!sp.isParticipating()) {
//	continue;
//}
//
//// serialize conservative locking
//sp.bookConservativeLocks();
//
//// create a new task for multi-thread
//CalvinStoredProcedureTask spt = new CalvinStoredProcedureTask(
//		call.getClientId(), call.getConnectionId(), call.getTxNum(),
//		sp);
//
//// hand over to a thread to run the task
//VanillaDb.taskMgr().runTask(spt);
