/* This file is part of VoltDB.
 * Copyright (C) 2008-2011 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb;

import static org.junit.Assert.*;

import org.junit.*;

import org.voltdb.SnapshotDaemon;
import org.voltdb.catalog.SnapshotSchedule;
import org.voltdb.client.ClientResponse;
import org.voltdb.utils.Pair;
import org.voltdb.sysprocs.SnapshotScan;
import org.voltdb.sysprocs.SnapshotSave;
import org.voltdb.VoltTable;
import org.voltdb.VoltTable.ColumnInfo;

public class TestSnapshotDaemon {

    @Test
    public void testBadFrequencyAndBasicInit() throws Exception {
        SnapshotDaemon noSnapshots = new SnapshotDaemon();
        assertNull(noSnapshots.processPeriodicWork(1));
        boolean threwException = false;
        try {
            noSnapshots.processClientResponse(null);
        } catch (Throwable t) {
            threwException = true;
        }
        assertTrue(threwException);


        final SnapshotSchedule schedule = new SnapshotSchedule();
        schedule.setFrequencyunit("q");
        threwException = false;
        SnapshotDaemon d = new SnapshotDaemon();
        try {
            d.makeActive(schedule);
        } catch (Throwable t) {
            threwException = true;
        }
        assertTrue(threwException);

        schedule.setFrequencyunit("s");
        d.makeActive(schedule);

        schedule.setFrequencyunit("m");
        d.makeActive(schedule);

        schedule.setFrequencyunit("h");

        d.makeActive(schedule);
        threwException = false;
        try {
            d.processClientResponse(null);
        } catch (Throwable t) {
            threwException = true;
        }
        assertTrue(threwException);
    }

    public SnapshotDaemon getBasicDaemon() {
        final SnapshotSchedule schedule = new SnapshotSchedule();
        schedule.setFrequencyunit("s");
        schedule.setFrequencyvalue(1);
        schedule.setPath("/tmp");
        schedule.setPrefix("woobie");
        schedule.setRetain(2);
        SnapshotDaemon d = new SnapshotDaemon();
        d.makeActive(schedule);
        return d;
    }

    public ClientResponse getFailureResponse() {
        return new ClientResponse() {

            @Override
            public Exception getException() {
                return null;
            }

            @Override
            public String getStatusString() {
                return "Super fail";
            }

            @Override
            public VoltTable[] getResults() {
                return null;
            }

            @Override
            public byte getStatus() {
                return ClientResponse.UNEXPECTED_FAILURE;
            }

            @Override
            public int getClusterRoundtrip() {
                return 0;
            }

            @Override
            public int getClientRoundtrip() {
                return 0;
            }

            @Override
            public byte getAppStatus() {
                return 0;
            }

            @Override
            public String getAppStatusString() {
                return null;
            }

        };
    }

    public ClientResponse getErrMsgResponse() {
        return new ClientResponse() {

            @Override
            public Exception getException() {
                return null;
            }

            @Override
            public VoltTable[] getResults() {
                VoltTable resultTable = new VoltTable(new ColumnInfo("ERR_MSG", VoltType.STRING));
                resultTable.addRow("It's a fail!");
                return  new VoltTable[] { resultTable };
            }

            @Override
            public byte getStatus() {
                return ClientResponse.SUCCESS;
            }

            @Override
            public int getClusterRoundtrip() {
                return 0;
            }

            @Override
            public int getClientRoundtrip() {
                return 0;
            }

            @Override
            public byte getAppStatus() {
                return 0;
            }

            @Override
            public String getAppStatusString() {
                return null;
            }

            @Override
            public String getStatusString() {
                return null;
            }

        };
    }

    @Test
    public void testFailedScan() throws Exception {

        SnapshotDaemon daemon = getBasicDaemon();

        Pair<String, Object[]>  work = daemon.processPeriodicWork(0);
        assertTrue("@SnapshotScan".equals(work.getFirst()));
        assertEquals(1, work.getSecond().length);
        assertTrue("/tmp".equals(work.getSecond()[0]));

        assertNull(daemon.processPeriodicWork(1));

        daemon.processClientResponse(getFailureResponse());
        assertNull(daemon.processPeriodicWork(2));
        assertNull(daemon.processClientResponse(null));

        daemon = getBasicDaemon();
        work = daemon.processPeriodicWork(0);

        daemon.processClientResponse(getErrMsgResponse());
        assertEquals(SnapshotDaemon.State.FAILURE, daemon.getState());
    }

    public ClientResponse getSuccessfulScanOneResult() {
        return new ClientResponse() {

            @Override
            public Exception getException() {
                return null;
            }

            @Override
            public String getStatusString() {
                return null;
            }

            @Override
            public VoltTable[] getResults() {
                VoltTable resultTable = new VoltTable(SnapshotScan.clientColumnInfo);
                resultTable.addRow(
                        "/tmp",
                        "woobie_",
                        0,
                        1,
                        0,
                        "",
                        "",
                        "",
                        "");
                return new VoltTable[] { resultTable, null, null };
            }

            @Override
            public byte getStatus() {
                return ClientResponse.SUCCESS;
            }

            @Override
            public int getClusterRoundtrip() {
                return 0;
            }

            @Override
            public int getClientRoundtrip() {
                return 0;
            }

            @Override
            public byte getAppStatus() {
                return 0;
            }

            @Override
            public String getAppStatusString() {
                return null;
            }

        };
    }

    public ClientResponse getSuccessfulScanThreeResults() {
        return new ClientResponse() {

            @Override
            public Exception getException() {
                return null;
            }

            @Override
            public String getStatusString() {
                return null;
            }

            @Override
            public VoltTable[] getResults() {
                VoltTable resultTable = new VoltTable(SnapshotScan.clientColumnInfo);
                resultTable.addRow(
                        "/tmp",
                        "woobie_2",
                        0,
                        2,
                        0,
                        "",
                        "",
                        "",
                        "");
                resultTable.addRow(
                        "/tmp",
                        "woobie_5",
                        0,
                        5,
                        0,
                        "",
                        "",
                        "",
                        "");
                resultTable.addRow(
                        "/tmp",
                        "woobie_3",
                        0,
                        3,
                        0,
                        "",
                        "",
                        "",
                        "");
                return new VoltTable[] { resultTable, null, null };
            }

            @Override
            public byte getStatus() {
                return ClientResponse.SUCCESS;
            }

            @Override
            public int getClusterRoundtrip() {
                return 0;
            }

            @Override
            public int getClientRoundtrip() {
                return 0;
            }

            @Override
            public byte getAppStatus() {
                return 0;
            }

            @Override
            public String getAppStatusString() {
                return null;
            }

        };
    }

    @Test
    public void testSuccessfulScan() throws Exception {
        SnapshotDaemon daemon = getBasicDaemon();

        Pair<String, Object[]>  work = daemon.processPeriodicWork(0);

        work = daemon.processClientResponse(getSuccessfulScanOneResult());
        assertNull(work);

        daemon = getBasicDaemon();
        daemon.processPeriodicWork(0);

        work = daemon.processClientResponse(getSuccessfulScanThreeResults());
        assertNotNull(work);
        assertTrue("@SnapshotDelete".equals(work.getFirst()));
        String path = ((String[])work.getSecond()[0])[0];
        String nonce = ((String[])work.getSecond()[1])[0];
        assertTrue("/tmp".equals(path));
        assertTrue("woobie_2".equals(nonce));
        assertNull(daemon.processPeriodicWork(3));

        assertNull(daemon.processClientResponse(getFailureResponse()));
        assertEquals(daemon.getState(), SnapshotDaemon.State.FAILURE);

        daemon = getBasicDaemon();
        daemon.processPeriodicWork(0);
        work = daemon.processClientResponse(getSuccessfulScanThreeResults());
        daemon.processClientResponse(getErrMsgResponse());
        assertEquals(daemon.getState(), SnapshotDaemon.State.WAITING);
    }

    @Test
    public void testDoSnapshot() throws Exception {
        SnapshotDaemon daemon = getBasicDaemon();
        long startTime = System.currentTimeMillis();
        Pair<String, Object[]>  work = daemon.processPeriodicWork(startTime);
        work = daemon.processClientResponse(getSuccessfulScanOneResult());
        assertNull(work);
        assertNull(daemon.processPeriodicWork(startTime + 2000));
        work = daemon.processPeriodicWork(startTime + 8000);
        assertNotNull(work);
        assertTrue("@SnapshotSave".equals(work.getFirst()));
        assertTrue("/tmp".equals(work.getSecond()[0]));
        assertTrue(((String)work.getSecond()[1]).startsWith("woobie_"));
        assertEquals(0, work.getSecond()[2]);

        assertNull(daemon.processClientResponse(getFailureResponse()));
        assertEquals(SnapshotDaemon.State.FAILURE, daemon.getState());

        daemon = getBasicDaemon();
        startTime = System.currentTimeMillis();
        assertNotNull(daemon.processPeriodicWork(startTime));
        assertNull(daemon.processClientResponse(getSuccessfulScanOneResult()));
        daemon.processPeriodicWork(startTime + 5000);
        daemon.processClientResponse(getErrMsgResponse());
        assertEquals(daemon.getState(), SnapshotDaemon.State.WAITING);

        daemon = getBasicDaemon();
        startTime = System.currentTimeMillis();
        assertNotNull(daemon.processPeriodicWork(startTime));
        assertNotNull(daemon.processClientResponse(getSuccessfulScanThreeResults()));
        assertNull(daemon.processClientResponse(getErrMsgResponse()));
        daemon.processPeriodicWork(startTime + 7500);
        assertNull(daemon.processPeriodicWork(startTime + 10000));
        daemon.processClientResponse(new ClientResponse() {

            @Override
            public Exception getException() {
                return null;
            }

            @Override
            public String getStatusString() {
                return null;
            }

            @Override
            public VoltTable[] getResults() {
                VoltTable result = new VoltTable(SnapshotSave.nodeResultsColumns);
                result.addRow(0, "desktop", "0", "FAILURE", "epic fail");
                return new VoltTable[] { result };
            }

            @Override
            public byte getStatus() {
                return ClientResponse.SUCCESS;
            }

            @Override
            public int getClusterRoundtrip() {
                return 0;
            }

            @Override
            public int getClientRoundtrip() {
                return 0;
            }

            @Override
            public byte getAppStatus() {
                return 0;
            }

            @Override
            public String getAppStatusString() {
                return null;
            }

        });
        assertEquals(SnapshotDaemon.State.WAITING, daemon.getState());
        work = daemon.processPeriodicWork(startTime + 15000);
        assertNotNull(work);
        assertTrue("@SnapshotSave".equals(work.getFirst()));
        daemon.processClientResponse(new ClientResponse() {

            @Override
            public Exception getException() {
                return null;
            }

            @Override
            public String getStatusString() {
                return null;
            }

            @Override
            public VoltTable[] getResults() {
                VoltTable result = new VoltTable(SnapshotSave.nodeResultsColumns);
                result.addRow(0, "desktop", "0", "SUCCESS", "epic success");
                return new VoltTable[] { result };
            }

            @Override
            public byte getStatus() {
                return ClientResponse.SUCCESS;
            }

            @Override
            public int getClusterRoundtrip() {
                return 0;
            }

            @Override
            public int getClientRoundtrip() {
                return 0;
            }

            @Override
            public byte getAppStatus() {
                return 0;
            }

            @Override
            public String getAppStatusString() {
                return null;
            }

        });

        work = daemon.processPeriodicWork(startTime + 20000);
        assertNotNull(work);
        assertTrue("@SnapshotDelete".equals(work.getFirst()));
    }
}
