package se.optimatika.optimisation.service.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@link OptClientV1#isServiceAvailable()} turns on this count, so a miscount is the difference between a
 * usable deployment reported as unavailable and an unusable one reported as ready.
 */
public class AvailableSolversTest {

    private static final String REALISTIC = "{\"build\":{\"module\":\"gcp\",\"built\":\"2026-08-01T05:42:25Z\",\"commit\":\"74fd4f6\","
            + "\"branch\":\"develop\",\"dirty\":\"false\"},\"runtime\":\"123MB/2threads x86_64\",\"licence\":\"Marketplace (unrestricted)\","
            + "\"solvers\":{\"probed\":true,\"available\":[\"LP-HiGHS\",\"LP-JVM\",\"MIQCQP-SCIP\",\"QCQP-Clarabel\"]}}";

    /**
     * The exact body a current server returns, captured from it rather than written by hand – this is the
     * contract {@link OptClientV1#isServiceAvailable()} reads, and nothing in this repository would notice it
     * changing shape.
     */
    @Test
    public void testCapturedServerResponse() {

        String body = "{\"build\":{\"module\":\"unknown\",\"built\":\"unknown\",\"commit\":\"f482efe\",\"branch\":\"develop\",\"dirty\":\"true\"},"
                + "\"runtime\":\"12GB/18threads aarch64 [48GB/18threads, 2MB/18threads, 256kB/1thread, 64kB/1thread]\",\"licence\":\"Marketplace (unrestricted)\",\"solvers\":{\"probed\":true,\"available\":[\"LP-Clarabel\",\"LP-HiGHS\",\"LP-JVM\",\"LP-SCIP\",\"MILP-HiGHS\",\"MILP-JVM\",\"MILP-SCIP\",\"MIQCLP-SCIP\",\"MIQCQP-SCIP\",\"MIQP-JVM\",\"MIQP-SCIP\",\"QCLP-Clarabel\",\"QCLP-SCIP\",\"QCQP-Clarabel\",\"QCQP-SCIP\",\"QP-Clarabel\",\"QP-HiGHS\",\"QP-JVM\",\"QP-SCIP\"]}}";

        Assertions.assertEquals(19, OptClientV1.parseAvailableSolvers(body));
    }

    @Test
    public void testCountsEveryEntry() {
        Assertions.assertEquals(4, OptClientV1.parseAvailableSolvers(REALISTIC));
    }

    /**
     * A quoted value earlier in the document must not be counted, and one after the array must not either.
     */
    @Test
    public void testCountsOnlyInsideTheArray() {

        String body = "{\"licence\":\"has \\\"available\\\" nowhere near it\",\"solvers\":{\"available\":[\"LP-JVM\"]},\"trailing\":\"x\"}";

        Assertions.assertEquals(1, OptClientV1.parseAvailableSolvers(body));
    }

    /** Still starting up: probing unfinished, but the built-in solvers are listed and they solve. */
    @Test
    public void testCountsWhileStillProbing() {

        String body = "{\"licence\":\"Standalone\",\"solvers\":{\"probed\":false,\"available\":[\"LP-JVM\",\"QP-JVM\",\"MILP-JVM\",\"MIQP-JVM\"]}}";

        Assertions.assertEquals(4, OptClientV1.parseAvailableSolvers(body));
    }

    @Test
    public void testEmptyArrayCountsAsNone() {
        Assertions.assertEquals(0, OptClientV1.parseAvailableSolvers("{\"solvers\":{\"probed\":true,\"available\":[]}}"));
    }

    /** The old plain-text environment response, and anything else that is not this document. */
    @Test
    public void testUnrecognisedBodyCountsAsNone() {

        Assertions.assertEquals(0, OptClientV1.parseAvailableSolvers("123MB/2threads x86_64\nLicence: Standalone (max size: 1000)"));
        Assertions.assertEquals(0, OptClientV1.parseAvailableSolvers("{}"));
        Assertions.assertEquals(0, OptClientV1.parseAvailableSolvers(""));
        Assertions.assertEquals(0, OptClientV1.parseAvailableSolvers(null));
    }

    /**
     * The runtime description carries brackets of its own, so a false match on the word must not be allowed
     * to run off and count whatever array it finds next.
     */
    @Test
    public void testWordInsideAnotherFieldIsNotTheArray() {

        String body = "{\"runtime\":\"123MB/2threads x86_64 [512MB/2threads, 4MB/2threads]\","
                + "\"licence\":\"no solvers available\",\"solvers\":{\"probed\":true,\"available\":[\"LP-JVM\",\"QP-JVM\"]}}";

        Assertions.assertEquals(2, OptClientV1.parseAvailableSolvers(body));
    }

}
