/* This file is part of VoltDB.
 * Copyright (C) 2008-2016 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
#include "ScanCopyOnWriteContext.h"

#include "storage/temptable.h"
#include "storage/tablefactory.h"
#include "storage/CopyOnWriteIterator.h"
#include "storage/persistenttable.h"
#include "storage/tableiterator.h"
#include "common/TupleOutputStream.h"
#include "common/FatalException.hpp"
#include "logging/LogManager.h"
#include <algorithm>
#include <cassert>
#include <iostream>

namespace voltdb {

/**
 * Constructor.
 */
ScanCopyOnWriteContext::ScanCopyOnWriteContext(
        PersistentTable &table,
        PersistentTableSurgeon &surgeon,
        int64_t totalTuples) :
             m_backedUpTuples(TableFactory::buildCopiedTempTable("COW of " + table.name(),
                                                                 &table, NULL)),
             m_table(table),
             m_surgeon(surgeon),
             m_pool(2097152, 320),
             m_tuple(table.schema()),
             m_finishedTableScan(false),
             m_totalTuples(totalTuples),
             m_tuplesRemaining(totalTuples),
             m_blocksCompacted(0),
             m_serializationBatches(0),
             m_inserts(0),
             m_deletes(0),
             m_updates(0)
{
}

/**
 * Destructor.
 */
ScanCopyOnWriteContext::~ScanCopyOnWriteContext()
{}


/**
 * Activation handler.
 */
void
ScanCopyOnWriteContext::handleActivation()
{
    if (m_finishedTableScan && m_tuplesRemaining == 0) {
        return;
    }
    m_surgeon.activateSnapshot();

    m_iterator.reset(new CopyOnWriteIterator(&m_table, &m_surgeon));
}

/**
 * Advance the COW iterator and return the next tuple
 */
bool ScanCopyOnWriteContext::advanceIterator(TableTuple &tuple) {
/*
 * If this is the table scan, check to see if the tuple is pending
 * delete and return the tuple if it is
 */
    assert(m_iterator != NULL);
    bool hasMore = m_iterator->next(tuple);
    if (hasMore && m_tuplesRemaining > 0) {
        m_tuplesRemaining--;
    }
    if (!hasMore && !m_finishedTableScan) {
        m_finishedTableScan = true;
        // Note that m_iterator no longer points to (or should reference) the CopyOnWriteIterator
        m_iterator.reset(m_backedUpTuples->makeIterator());
        hasMore = m_iterator->next(tuple);
        if (hasMore && m_tuplesRemaining > 0) {
            m_tuplesRemaining--;
        }
    }
    if (!hasMore && m_finishedTableScan) {
        cleanup();
    }
    completePassIfDone(hasMore);

    return hasMore;
}

/**
 * Cleanup
 */
bool ScanCopyOnWriteContext::cleanupTuple(TableTuple &tuple, bool deleteTuple) {

    if (tuple.isPendingDelete()) {
        assert(!tuple.isPendingDeleteOnUndoRelease());
        CopyOnWriteIterator *iter = static_cast<CopyOnWriteIterator*>(m_iterator.get());
        //Save the extra lookup if possible
        m_surgeon.deleteTupleStorage(tuple, iter->m_currentBlock);
    }
    /*
     * Delete a moved tuple?
     * This is used for Elastic rebalancing, which is wrapped in a transaction.
     * The delete for undo is generic enough to support this operation.
     */
    else if (deleteTuple) {
        m_surgeon.deleteTupleForUndo(tuple.address(), true);
    }
    return true;
}

/**
 * If done serializing or scanning, complete pass of iterator
 */
void ScanCopyOnWriteContext::completePassIfDone(bool hasMore) {
    if (m_tuplesRemaining == 0) {
        /*
         * CAUTION: m_iterator->next() is NOT side-effect free!!! It also
         * returns the block back to the table if the call causes it to go
         * over the boundary of used tuples. In case it actually returned
         * the very last tuple in the table last time it's called, the block
         * is still hanging around. So we need to call it again to return
         * the block here.
         */

        if (hasMore) {
            PersistentTable &table = m_table;
            TableTuple tuple(table.schema());
            bool hasAnother = m_iterator->next(tuple);
            if (hasAnother) {
                assert(false);
            }
        }
    }
}

/**
 * Returns true for success, false if there was a serialization error
 */
bool ScanCopyOnWriteContext::cleanup() {
    PersistentTable &table = m_table;
    size_t allPendingCnt = m_surgeon.getSnapshotPendingBlockCount();
    size_t pendingLoadCnt = m_surgeon.getSnapshotPendingLoadBlockCount();
    if (m_tuplesRemaining > 0 || allPendingCnt > 0 || pendingLoadCnt > 0) {
        int32_t skippedDirtyRows = 0;
        int32_t skippedInactiveRows = 0;
        if (!m_finishedTableScan) {
            skippedDirtyRows = reinterpret_cast<CopyOnWriteIterator*>(m_iterator.get())->m_skippedDirtyRows;
            skippedInactiveRows = reinterpret_cast<CopyOnWriteIterator*>(m_iterator.get())->m_skippedInactiveRows;
        }

        char message[1024 * 16];
        snprintf(message, 1024 * 16,
                 "serializeMore(): tuple count > 0 after streaming:\n"
                 "Table name: %s\n"
                 "Table type: %s\n"
                 "Original tuple count: %jd\n"
                 "Active tuple count: %jd\n"
                 "Remaining tuple count: %jd\n"
                 "Pending block count: %jd\n"
                 "Pending load block count: %jd\n"
                 "Compacted block count: %jd\n"
                 "Dirty insert count: %jd\n"
                 "Dirty delete count: %jd\n"
                 "Dirty update count: %jd\n"
                 "Partition column: %d\n"
                 "Skipped dirty rows: %d\n"
                 "Skipped inactive rows: %d\n",
                 table.name().c_str(),
                 table.tableType().c_str(),
                 (intmax_t)m_totalTuples,
                 (intmax_t)table.activeTupleCount(),
                 (intmax_t)m_tuplesRemaining,
                 (intmax_t)allPendingCnt,
                 (intmax_t)pendingLoadCnt,
                 (intmax_t)m_blocksCompacted,
                 (intmax_t)m_inserts,
                 (intmax_t)m_deletes,
                 (intmax_t)m_updates,
                 table.partitionColumn(),
                 skippedDirtyRows,
                 skippedInactiveRows);

        // If m_tuplesRemaining is not 0, we somehow corrupted the iterator. To make a best effort
        // at continuing unscathed, we will make sure all the blocks are back in the non-pending snapshot
        // lists and hope that the next snapshot handles everything correctly. We assume that the iterator
        // at least returned it's currentBlock to the lists.
        if (allPendingCnt > 0) {
            // We have orphaned or corrupted some tables. Let's make them pristine.
            TBMapI iter = m_surgeon.getData().begin();
            while (iter != m_surgeon.getData().end()) {
                m_surgeon.snapshotFinishedScanningBlock(iter.data(), TBPtr());
                iter++;
            }
        }
        if (!m_surgeon.blockCountConsistent()) {
            throwFatalException("%s", message);
        }
        else {
            LogManager::getThreadLogger(LOGGERID_HOST)->log(LOGLEVEL_ERROR, message);
            m_tuplesRemaining = 0;
            return false;
        }
    } else if (m_tuplesRemaining < 0)  {
        // -1 is used for tests when we don't bother counting. Need to force it to 0 here.
        m_tuplesRemaining = 0;
    }
    return true;
}

bool ScanCopyOnWriteContext::notifyTupleDelete(TableTuple &tuple) {
    assert(m_iterator != NULL);

    if (tuple.isDirty() || m_finishedTableScan) {
        return true;
    }
    // This is a 'loose' count of the number of deletes because COWIterator could be past this
    // point in the block.
    m_deletes++;

    /**
     * Now check where this is relative to the COWIterator.
     */
    CopyOnWriteIterator *iter = reinterpret_cast<CopyOnWriteIterator*>(m_iterator.get());
    return !iter->needToDirtyTuple(tuple.address());
}

void ScanCopyOnWriteContext::markTupleDirty(TableTuple tuple, bool newTuple) {
    assert(m_iterator != NULL);

    /**
     * If this an update or a delete of a tuple that is already dirty then no further action is
     * required.
     */
    if (!newTuple && tuple.isDirty()) {
        return;
    }

    /**
     * If the table has been scanned already there is no need to continue marking tuples dirty
     * If the tuple is dirty then it has already been backed up.
     */
    if (m_finishedTableScan) {
        tuple.setDirtyFalse();
        return;
    }

    /**
     * Now check where this is relative to the COWIterator.
     */
    CopyOnWriteIterator *iter = reinterpret_cast<CopyOnWriteIterator*>(m_iterator.get());
    if (iter->needToDirtyTuple(tuple.address())) {
        tuple.setDirtyTrue();

        if (newTuple) {
            /**
             * Don't back up a newly introduced tuple, just mark it as dirty.
             */
            m_inserts++;
        }
        else {
            m_updates++;
            m_backedUpTuples->insertTempTupleDeepCopy(tuple, &m_pool);
        }
    } else {
        tuple.setDirtyFalse();
        return;
    }
}

void ScanCopyOnWriteContext::notifyBlockWasCompactedAway(TBPtr block) {
    assert(m_iterator != NULL);
    if (m_finishedTableScan) {
        // There was a compaction while we are iterating through the m_backedUpTuples
        // TempTable. Don't do anything because the passed in block is a PersistentTable
        // block
        return;
    }
    m_blocksCompacted++;
    CopyOnWriteIterator *iter = static_cast<CopyOnWriteIterator*>(m_iterator.get());
    TBPtr nextBlock = iter->m_blockIterator.data();
    TBPtr newNextBlock = iter->m_blockIterator.data();
    iter->notifyBlockWasCompactedAway(block);
}

bool ScanCopyOnWriteContext::notifyTupleInsert(TableTuple &tuple) {
    markTupleDirty(tuple, true);
    return true;
}

bool ScanCopyOnWriteContext::notifyTupleUpdate(TableTuple &tuple) {
    markTupleDirty(tuple, false);
    return true;
}

/*
 * Recalculate how many tuples are remaining and compare to the countdown value.
 * This method does not work once we're in the middle of the temp table.
 * Only call it while m_finishedTableScan==false.
 */
void ScanCopyOnWriteContext::checkRemainingTuples(const std::string &label) {
    assert(m_iterator != NULL);
    assert(!m_finishedTableScan);
    intmax_t count1 = static_cast<CopyOnWriteIterator*>(m_iterator.get())->countRemaining();
    TableTuple tuple(m_table.schema());
    boost::scoped_ptr<TupleIterator> iter(m_backedUpTuples->makeIterator());
    intmax_t count2 = 0;
    while (iter->next(tuple)) {
        count2++;
    }
    if (m_tuplesRemaining != count1 + count2) {
        char errMsg[1024 * 16];
        snprintf(errMsg, 1024 * 16,
                 "CopyOnWriteContext::%s remaining tuple count mismatch: "
                 "table=%s partcol=%d count=%jd count1=%jd count2=%jd "
                 "expected=%jd compacted=%jd batch=%jd "
                 "inserts=%jd updates=%jd",
                 label.c_str(), m_table.name().c_str(), m_table.partitionColumn(),
                 count1 + count2, count1, count2, (intmax_t)m_tuplesRemaining,
                 (intmax_t)m_blocksCompacted, (intmax_t)m_serializationBatches,
                 (intmax_t)m_inserts, (intmax_t)m_updates);
        LogManager::getThreadLogger(LOGGERID_HOST)->log(LOGLEVEL_ERROR, errMsg);
    }
}

}
