/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.commons.transaction.xa;

import java.util.HashMap;
import java.util.Map;
import java.util.Collections;

import javax.transaction.Status;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.transaction.TransactionalResource;

/**
 * Abstract XAResource doing all the tedious tasks shared by many XAResouce
 * implementations.
 * 
 * @version $Id: AbstractXAResource.java 493628 2007-01-07 01:42:48Z joerg $
 */
public abstract class AbstractXAResource implements XAResource, Status {

    private Log logger = LogFactory.getLog(getClass());

    // there might be at least one active transaction branch per thread
    private ThreadLocal activeTransactionBranch = new ThreadLocal();

    private Map suspendedContexts = Collections.synchronizedMap(new HashMap());

    private Map activeContexts = Collections.synchronizedMap(new HashMap());

    public abstract boolean isSameRM(XAResource xares) throws XAException;

    public abstract Xid[] recover(int flag) throws XAException;

    public void forget(Xid xid) throws XAException {
        if (logger.isDebugEnabled()) {
            logger.debug("Forgetting transaction branch " + xid);
        }
        TransactionalResource ts = getTransactionalResource(xid);
        if (ts == null) {
            throw new XAException(XAException.XAER_NOTA);
        }
        setCurrentlyActiveTransactionalResource(null);
        removeActiveTransactionalResource(xid);
        removeSuspendedTransactionalResource(xid);
    }

    public void commit(Xid xid, boolean onePhase) throws XAException {
        TransactionalResource ts = getTransactionalResource(xid);
        if (ts == null) {
            throw new XAException(XAException.XAER_NOTA);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Committing transaction branch " + ts);
        }

        if (ts.isTransactionMarkedForRollback()) {
            throw new XAException(XAException.XA_RBROLLBACK);
        }

        if (ts.isTransactionPrepared()) {
            if (onePhase) {
                ts.prepareTransaction();
            } else {
                throw new XAException(XAException.XAER_PROTO);
            }
        }
        ts.commitTransaction();
        setCurrentlyActiveTransactionalResource(null);
        removeActiveTransactionalResource(xid);
        removeSuspendedTransactionalResource(xid);
    }

    public void rollback(Xid xid) throws XAException {
        TransactionalResource ts = getTransactionalResource(xid);
        if (ts == null) {
            throw new XAException(XAException.XAER_NOTA);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Rolling back transaction branch " + ts);
        }

        ts.rollbackTransaction();
        setCurrentlyActiveTransactionalResource(null);
        removeActiveTransactionalResource(xid);
        removeSuspendedTransactionalResource(xid);
    }

    public int prepare(Xid xid) throws XAException {
        TransactionalResource ts = getTransactionalResource(xid);
        if (ts == null) {
            throw new XAException(XAException.XAER_NOTA);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Preparing transaction branch " + ts);
        }

        if (ts.isTransactionMarkedForRollback()) {
            throw new XAException(XAException.XA_RBROLLBACK);
        }

        int result;
        boolean prepared = ts.prepareTransaction();
        if (prepared) {
            if (ts.isReadOnlyTransaction()) {
                result = XA_RDONLY;
            } else {
                result = XA_OK;
            }
        } else {
            throw new XAException(XAException.XA_RBROLLBACK);
        }

        if (result == XA_RDONLY) {
            commit(xid, false);
        }

        return result;
    }

    public void end(Xid xid, int flags) throws XAException {
        TransactionalResource ts = getActiveTransactionalResource(xid);
        if (ts == null) {
            throw new XAException(XAException.XAER_NOTA);
        }
        if (getCurrentlyActiveTransactionalResource() == null) {
            throw new XAException(XAException.XAER_INVAL);
        }
        if (logger.isDebugEnabled()) {
            logger
                    .debug(new StringBuffer(128).append("Thread ").append(Thread.currentThread())
                            .append(
                                    flags == TMSUSPEND ? " suspends" : flags == TMFAIL ? " fails"
                                            : " ends").append(
                                    " work on behalf of transaction branch ").append(ts).toString());
        }

        switch (flags) {
        case TMSUSPEND:
            // FIXME: This would require action on the transactional resource,
            // but we just can't do that
            addSuspendedTransactionalResource(xid, ts);
            removeActiveTransactionalResource(xid);
            break;
        case TMFAIL:
            ts.markTransactionForRollback();
            break;
        case TMSUCCESS:
            break;
        }
        setCurrentlyActiveTransactionalResource(null);
    }

    public void start(Xid xid, int flags) throws XAException {
        if (getCurrentlyActiveTransactionalResource() != null) {
            throw new XAException(XAException.XAER_INVAL);
        }
        if (logger.isDebugEnabled()) {
            logger.debug(new StringBuffer(128).append("Thread ").append(Thread.currentThread())
                    .append(
                            flags == TMNOFLAGS ? " starts" : flags == TMJOIN ? " joins"
                                    : " resumes").append(" work on behalf of transaction branch ")
                    .append(xid).toString());
        }

        TransactionalResource ts;
        switch (flags) {
        // a new transaction
        case TMNOFLAGS:
        case TMJOIN:
        default:
            try {
                ts = createTransactionResource(xid);
                ts.startTransaction();
            } catch (Exception e) {
                logger.error("Could not create new transactional  resource", e);
                throw new XAException(e.getMessage());
            }
            break;
        case TMRESUME:
            ts = getSuspendedTransactionalResource(xid);
            if (ts == null) {
                throw new XAException(XAException.XAER_NOTA);
            }
            // FIXME: This would require action on the transactional resource,
            // but we just can't do that
            removeSuspendedTransactionalResource(xid);
            break;
        }
        setCurrentlyActiveTransactionalResource(ts);
        addAcitveTransactionalResource(xid, ts);
    }

    abstract protected TransactionalResource createTransactionResource(Xid xid) throws Exception;

    protected TransactionalResource getCurrentlyActiveTransactionalResource() {
        TransactionalResource context = (TransactionalResource) activeTransactionBranch.get();
        return context;
    }

    protected void setCurrentlyActiveTransactionalResource(TransactionalResource context) {
        activeTransactionBranch.set(context);
    }

    protected TransactionalResource getTransactionalResource(Xid xid) {
        TransactionalResource ts = getActiveTransactionalResource(xid);
        if (ts != null)
            return ts;
        else
            return getSuspendedTransactionalResource(xid);
    }

    protected TransactionalResource getActiveTransactionalResource(Xid xid) {
        return (TransactionalResource) activeContexts.get(xid);
    }

    protected TransactionalResource getSuspendedTransactionalResource(Xid xid) {
        return (TransactionalResource) suspendedContexts.get(xid);
    }

    protected void addAcitveTransactionalResource(Xid xid, TransactionalResource txContext) {
        activeContexts.put(xid, txContext);
    }

    protected void addSuspendedTransactionalResource(Xid xid, TransactionalResource txContext) {
        suspendedContexts.put(xid, txContext);
    }

    protected void removeActiveTransactionalResource(Xid xid) {
        activeContexts.remove(xid);
    }

    protected void removeSuspendedTransactionalResource(Xid xid) {
        suspendedContexts.remove(xid);
    }

}
