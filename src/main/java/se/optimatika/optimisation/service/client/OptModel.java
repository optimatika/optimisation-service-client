package se.optimatika.optimisation.service.client;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Builder for optimisation models that are solved remotely, by the Optimisation Service.
 * <p>
 * Use the factory methods to create variables ({@link #newRealVariable}, {@link #newIntegerVariable},
 * {@link #newBinaryVariable}), define constraints ({@link #newConstraint}), and set the objective function
 * ({@link #getObjective()}). Then call {@link #maximise()} or {@link #minimise()} to submit the model to the
 * server and obtain a {@link Future} that completes with the {@link OptResult}.
 * <p>
 * The model is serialised to the EBM (Expression-Based Model) format and sent to the server via
 * {@link OptClientV1}. The server solves the model and the result is polled asynchronously. On completion,
 * solution values are written back to the variables and any registered value receivers are notified.
 * <p>
 * You create instances by first creating a {@link OptClientV1} and then calling
 * {@link OptClientV1#newModel()}.
 *
 * @see OptClientV1
 */
public final class OptModel {

    /** Callback that receives a binary variable's solution value after optimisation. */
    @FunctionalInterface
    public interface BinaryReceiver {

        void receive(boolean value);

    }

    /** Callback that receives an integer variable's solution value after optimisation. */
    @FunctionalInterface
    public interface IntegerReceiver {

        void receive(int value);

    }

    /** Callback that receives a real variable's solution value after optimisation. */
    @FunctionalInterface
    public interface RealReceiver {

        void receive(BigDecimal value);

    }

    /**
     * The {@link Future} returned by {@link OptModel#minimise()} and {@link OptModel#maximise()}.
     * <p>
     * {@link #cancel(boolean)} with {@code mayInterruptIfRunning} set abandons the solve on the server as
     * well as locally, the way {@link OptClientV1#abort(String)} does. Without it only the local side is
     * abandoned: polling stops and the {@link Future} reports cancelled, but the server carries on solving.
     * That is the usual reading of the flag – leave a task that has already started alone – but note that the
     * queue key is not exposed here, so a solve left running that way cannot be reached again.
     * <p>
     * Cancelling before the model has even been submitted works too: the abort is sent as soon as the server
     * hands back a key.
     * <p>
     * The abort is sent in the background, so {@link #cancel(boolean)} does not wait for it. On a service
     * that runs as several instances it can reach one that does not know the solve. It is then sent again,
     * after 1 s, 2 s, 4 s and so on, at most 2 minutes apart, until the instance solving it answers, for at
     * most 10 minutes. If none of them gets there, a warning is logged, and the solve runs on until it
     * finishes or reaches its time limit. The model is never submitted again.
     */
    static final class RemoteSolve extends CompletableFuture<OptResult> {

        private static final System.Logger LOGGER = System.getLogger(OptModel.class.getName());
        static final long ABORT_FIRST_GAP_MILLIS = 1_000L;
        static final long ABORT_GIVE_UP_MILLIS = 600_000L;

        static final long ABORT_MAX_GAP_MILLIS = 120_000L;

        private final AtomicBoolean myAbortSent = new AtomicBoolean(false);
        private volatile boolean myAbortWanted = false;
        private final OptClientV1 myClient;
        private volatile String myKey = null;
        private volatile Thread myPoller = null;

        RemoteSolve(final OptClientV1 client) {
            super();
            myClient = client;
        }

        @Override
        public boolean cancel(final boolean mayInterruptIfRunning) {

            boolean retVal = super.cancel(mayInterruptIfRunning);

            if (mayInterruptIfRunning) {

                myAbortWanted = true;
                this.abortRemote();

                // Wakes the poll loop out of its sleep, which can be 10s long, rather than leaving a thread
                // to notice the cancellation whenever it next happens to look. Null until the model has been
                // submitted, because interrupting that would lose the key to a solve the server had already
                // accepted – such a cancellation is carried by myAbortWanted instead, and sent as an abort
                // the moment the key arrives.
                Thread poller = myPoller;
                if (poller != null) {
                    poller.interrupt();
                }
            }

            return retVal;
        }

        /**
         * Sends the abort, once, and only once both halves are in place – something has asked for it, and the
         * server has given us a key to name. Called from both sides because either can be the last to arrive.
         */
        private void abortRemote() {

            String key = myKey;

            if (myAbortWanted && key != null && myAbortSent.compareAndSet(false, true)) {
                Thread aborter = new Thread(() -> this.abortUntilAnswered(key), "optimisation-abort-" + key);
                aborter.setDaemon(true);
                aborter.start();
            }
        }

        /**
         * Sends the abort until the instance solving it answers, with growing gaps, or gives up. Only that
         * instance answers with the solve's status, {@code DONE}: it knows the solve, finished or not.
         * Another answers 404, and an overloaded service may answer with an error of its own.
         */
        private void abortUntilAnswered(final String key) {

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ABORT_GIVE_UP_MILLIS);
            long gap = ABORT_FIRST_GAP_MILLIS;
            int attempts = 0;

            while (true) {

                attempts++;

                try {
                    String answer = myClient.abort(key);
                    if (answer != null && answer.contains("\"status\":\"DONE\"")) {
                        return;
                    }
                } catch (IOException cause) {
                    // Not sent, or no answer. Tried again like a miss.
                } catch (InterruptedException cause) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(gap) > deadline) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "The abort of {0} did not reach the service instance solving it, in {1} attempts. It runs on until it finishes or reaches its time limit.",
                            key, attempts);
                    return;
                }

                try {
                    Thread.sleep(gap);
                } catch (InterruptedException cause) {
                    Thread.currentThread().interrupt();
                    return;
                }

                gap = Math.min(2L * gap, ABORT_MAX_GAP_MILLIS);
            }
        }

        /**
         * The queue key, once the server has handed it back – null until then. Not public: a solve started
         * here is followed and cancelled through the {@link Future}, and this is for this package's tests.
         */
        String key() {
            return myKey;
        }

        void setKey(final String key) {
            myKey = key;
            this.abortRemote();
        }

        void setPoller(final Thread poller) {
            myPoller = poller;
        }

    }

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final BigDecimal BD_HALF = new BigDecimal("0.5");
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "opt-serv");
        t.setDaemon(true);
        return t;
    });
    private static final MathContext MATH_CONTEXT = new MathContext(12, RoundingMode.HALF_EVEN);

    private static String asString(final BigDecimal value) {
        return value != null ? value.toPlainString() : "";
    }

    private static String name() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        char[] chars = new char[8];
        for (int i = 0; i < 8; i++) {
            chars[i] = ALPHANUMERIC.charAt(rnd.nextInt(ALPHANUMERIC.length()));
        }
        return new String(chars);
    }

    static boolean booleanValue(final BigDecimal value) {
        return value != null && value.compareTo(BD_HALF) > 0;
    }

    static boolean booleanValue(final long value) {
        return value >= 1L;
    }

    static double doubleValue(final BigDecimal value) {
        if (value != null) {
            return value.doubleValue();
        } else {
            return 0.0;
        }
    }

    static float floatValue(final BigDecimal value) {
        if (value != null) {
            return value.floatValue();
        } else {
            return 0.0F;
        }
    }

    static int intValue(final BigDecimal value) {
        if (value != null) {
            return value.setScale(0, RoundingMode.HALF_EVEN).intValue();
        } else {
            return 0;
        }
    }

    static long longValue(final BigDecimal value) {
        if (value != null) {
            return value.setScale(0, RoundingMode.HALF_EVEN).longValue();
        } else {
            return 0L;
        }
    }

    static short shortValue(final BigDecimal value) {
        if (value != null) {
            return value.setScale(0, RoundingMode.HALF_EVEN).shortValue();
        }
        return 0;
    }

    static BigDecimal toBigDecimal(final double value) {
        return new BigDecimal(value, MATH_CONTEXT).setScale(12, RoundingMode.HALF_EVEN).stripTrailingZeros();
    }

    static BigDecimal value(final boolean value) {
        return value ? BigDecimal.ONE : BigDecimal.ZERO;
    }

    static BigDecimal value(final double value) {
        return OptModel.toBigDecimal(value);
    }

    static BigDecimal value(final long value) {
        return BigDecimal.valueOf(value);
    }

    private final OptClientV1 myClient;
    private final Map<String, Consumer<BigDecimal>> myConsumers = new HashMap<>();
    private final List<OptExpression> myExpressions = new ArrayList<>();
    private final OptObjective myObjective;
    private final List<OptVariable> myVariables = new ArrayList<>();

    OptModel(final OptClientV1 client) {
        super();
        myClient = Objects.requireNonNull(client);
        myObjective = new OptObjective(this);
    }

    /**
     * Exports this model serialised in the specified file format.
     *
     * @param format the desired output format: {@code "EBM"}, {@code "LP"}, or {@code "MPS"}
     * @return an {@link InputStream} containing the serialised model
     */
    public InputStream exportModel(final String format) {

        byte[] input = this.toBytesOfEBM();
        byte[] output;

        if ("EBM".equalsIgnoreCase(format)) {
            output = input;
        } else {
            output = myClient.translate(input, "EBM", format);
        }

        return new ByteArrayInputStream(output);
    }

    /**
     * Returns this model's objective function. Each model has exactly one objective. Whether it is minimised
     * or maximised is decided when solving, by {@link #minimise()} or {@link #maximise()}.
     */
    public OptObjective getObjective() {
        return myObjective;
    }

    /**
     * Submits this model to the server for maximisation and returns a {@link Future} that completes with the
     * {@link OptResult}. On completion, solution values are written back to the variables.
     * <p>
     * Calling {@code cancel(true)} on the returned {@link Future} abandons the solve on the server as well as
     * locally. See {@link #minimise()} for what the flag decides, and for when the {@link Future} completes
     * exceptionally.
     */
    public Future<OptResult> maximise() {
        return this.optimise(true);
    }

    /**
     * Submits this model to the server for minimisation and returns a {@link Future} that completes with the
     * {@link OptResult}. On completion, solution values are written back to the variables.
     * <p>
     * The returned {@link Future} can be cancelled, and the {@code mayInterruptIfRunning} flag decides how
     * far that reaches:
     * <ul>
     * <li>{@code cancel(true)} also aborts the solve on the server, freeing the worker it occupies for the
     * next model in the queue. Use this for a solve that is taking longer than you are willing to wait. The
     * abort is sent in the background, and sent again for up to 10 minutes if it reaches a service instance
     * other than the one solving the model; if none gets there, a warning is logged.</li>
     * <li>{@code cancel(false)} abandons only the local side. Polling stops and the {@link Future} reports
     * cancelled, but the server keeps solving, and since the queue key is not exposed here that solve cannot
     * be reached again.</li>
     * </ul>
     * Either way the {@link Future} never completes with a result, and solution values are not written back
     * to the variables.
     * <p>
     * The {@link Future} completes exceptionally – {@code get()} throws an {@code ExecutionException} with an
     * {@link IOException} as its cause – when the service cannot be reached or does not answer in time,
     * answers with an error (429 when its queue is full, say), does not accept the model, or no longer knows
     * the solve: it has expired, or a reconnect reached another instance of the service. The
     * model is not submitted again; that is the caller's decision. A model the service refuses, for its size
     * say, completes normally, with a result that is not feasible.
     */
    public Future<OptResult> minimise() {
        return this.optimise(false);
    }

    public OptVariable.BinaryVariable newBinaryVariable() {
        return this.doNewBinary(OptModel.name(), null);
    }

    public OptVariable.BinaryVariable newBinaryVariable(final BinaryReceiver valueReceiver) {
        return this.doNewBinary(OptModel.name(), valueReceiver);
    }

    public OptVariable.BinaryVariable newBinaryVariable(final String name) {
        return this.doNewBinary(name, null);
    }

    public OptVariable.BinaryVariable newBinaryVariable(final String name, final BinaryReceiver valueReceiver) {
        return this.doNewBinary(name, valueReceiver);
    }

    public OptConstraint newConstraint() {
        return this.newConstraint(OptModel.name());
    }

    public OptConstraint newConstraint(final String name) {
        OptConstraint constraint = new OptConstraint(name);
        myExpressions.add(constraint);
        return constraint;
    }

    public OptVariable.IntegerVariable newIntegerVariable() {
        return this.doNewInteger(OptModel.name(), null);
    }

    public OptVariable.IntegerVariable newIntegerVariable(final IntegerReceiver valueReceiver) {
        return this.doNewInteger(OptModel.name(), valueReceiver);
    }

    public OptVariable.IntegerVariable newIntegerVariable(final String name) {
        return this.doNewInteger(name, null);
    }

    public OptVariable.IntegerVariable newIntegerVariable(final String name, final IntegerReceiver valueReceiver) {
        return this.doNewInteger(name, valueReceiver);
    }

    public OptVariable.RealVariable newRealVariable() {
        return this.doNewReal(OptModel.name(), null);
    }

    public OptVariable.RealVariable newRealVariable(final RealReceiver valueReceiver) {
        return this.doNewReal(OptModel.name(), valueReceiver);
    }

    public OptVariable.RealVariable newRealVariable(final String name) {
        return this.doNewReal(name, null);
    }

    public OptVariable.RealVariable newRealVariable(final String name, final RealReceiver valueReceiver) {
        return this.doNewReal(name, valueReceiver);
    }

    /**
     * @deprecated Use {@link #getObjective()}, named as in ojAlgo's {@code ExpressionsBasedModel}. To be
     *             removed in 2.0.
     */
    @Deprecated(since = "1.1.0", forRemoval = true)
    public OptObjective objective() {
        return this.getObjective();
    }

    @Override
    public String toString() {
        return "Variables: " + myVariables.size() + ", Expressions: " + myExpressions.size();
    }

    private OptVariable.BinaryVariable doNewBinary(final String name, final BinaryReceiver valueReceiver) {
        Objects.requireNonNull(name);
        if (valueReceiver != null) {
            myConsumers.put(name, v -> valueReceiver.receive(OptModel.booleanValue(v)));
        }
        OptVariable.BinaryVariable variable = new OptVariable.BinaryVariable(name, myVariables.size());
        myVariables.add(variable);
        return variable;
    }

    private OptVariable.IntegerVariable doNewInteger(final String name, final IntegerReceiver valueReceiver) {
        Objects.requireNonNull(name);
        if (valueReceiver != null) {
            myConsumers.put(name, v -> valueReceiver.receive(OptModel.intValue(v)));
        }
        OptVariable.IntegerVariable variable = new OptVariable.IntegerVariable(name, myVariables.size());
        myVariables.add(variable);
        return variable;
    }

    private OptVariable.RealVariable doNewReal(final String name, final RealReceiver valueReceiver) {
        Objects.requireNonNull(name);
        if (valueReceiver != null) {
            myConsumers.put(name, v -> valueReceiver.receive(v));
        }
        OptVariable.RealVariable variable = new OptVariable.RealVariable(name, myVariables.size());
        myVariables.add(variable);
        return variable;
    }

    private OptResult handleResult(final Map<String, Object> response) {

        OptResult result = (OptResult) response.get(OptClientV1.RESULT);

        if (result == null) {
            return new OptResult("FAILED", BigDecimal.ZERO, List.of());
        }

        List<BigDecimal> solution = result.getSolution();

        if (result.isFeasible() && solution != null && solution.size() == myVariables.size()) {
            for (int i = 0; i < solution.size(); i++) {
                BigDecimal solValue = solution.get(i);
                OptVariable variable = myVariables.get(i);
                variable.setValue(solValue);
                Consumer<BigDecimal> consumer = myConsumers.get(variable.getName());
                if (consumer != null) {
                    consumer.accept(solValue);
                }
            }
        }

        BigDecimal constant = myObjective.getConstant();
        if (constant != null) {
            result = result.withAdjustedValue(constant);
        }

        return result;
    }

    private byte[] toBytesOfEBM() {

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {

            for (OptVariable v : myVariables) {
                writer.write('V');
                writer.write('\t');
                writer.write(v.getName());
                writer.write('\t');
                writer.write(OptModel.asString(v.getLower()));
                writer.write('\t');
                writer.write(OptModel.asString(v.getUpper()));
                writer.write('\t');
                writer.write('\t');
                writer.write(v.isInteger() ? "true" : "false");
                writer.write('\t');
                writer.write(OptModel.asString(v.getValue()));
                writer.newLine();
            }

            for (OptExpression e : myExpressions) {

                BigDecimal constant = e.getConstant();
                BigDecimal lower = e.getLower();
                BigDecimal upper = e.getUpper();

                if (constant != null) {
                    if (lower != null) {
                        lower = lower.subtract(constant);
                    }
                    if (upper != null) {
                        upper = upper.subtract(constant);
                    }
                }

                writer.write('E');
                writer.write('\t');
                writer.write(e.getName());
                writer.write('\t');
                writer.write(OptModel.asString(lower));
                writer.write('\t');
                writer.write(OptModel.asString(upper));
                writer.write('\t');
                writer.write(e instanceof OptObjective ? "1" : "");
                writer.newLine();

                for (Map.Entry<Integer, BigDecimal> entry : e.getLinearEntrySet()) {
                    writer.write('L');
                    writer.write('\t');
                    writer.write(Integer.toString(entry.getKey()));
                    writer.write('\t');
                    writer.write(entry.getValue().toPlainString());
                    writer.newLine();
                }

                for (Map.Entry<Long, BigDecimal> entry : e.getQuadraticEntrySet()) {
                    writer.write('Q');
                    writer.write('\t');
                    writer.write(Integer.toString(OptExpression.decodeRow(entry.getKey())));
                    writer.write('\t');
                    writer.write(Integer.toString(OptExpression.decodeCol(entry.getKey())));
                    writer.write('\t');
                    writer.write(entry.getValue().toPlainString());
                    writer.newLine();
                }
            }

            writer.flush();
            return baos.toByteArray();

        } catch (IOException cause) {
            throw new RuntimeException(cause);
        }
    }

    void addExpression(final OptExpression expression) {
        myExpressions.add(expression);
    }

    int countVariables() {
        return myVariables.size();
    }

    OptVariable getVariable(final int index) {
        return myVariables.get(index);
    }

    Future<OptResult> optimise(final boolean maximize) {

        AtomicLong counter = new AtomicLong();
        RemoteSolve future = new RemoteSolve(myClient);

        EXECUTOR.execute(() -> {

            try {

                if (future.isCancelled()) {
                    return;
                }

                // Deliberately not interruptible. An interrupt here would surface as an InterruptedException
                // out of the HTTP send – and the request may well have reached the server all the same. There
                // would then be a solve running that nothing could name, let alone abort. So a cancellation
                // arriving during submission is recorded and acted on below rather than thrown at this call.
                Map<String, Object> response = OptClientV1.parseResponse(myClient.putOnQueue(this.toBytesOfEBM(), "EBM", maximize));
                String key = (String) response.get(OptClientV1.KEY);
                String status = (String) response.get(OptClientV1.STATUS);

                if (key == null) {
                    throw new IOException("The service did not accept the model: " + response);
                }

                // Hands the key over before the first poll, so that a cancellation that arrived while the
                // model was in flight has something to abort, and sends that abort now.
                future.setKey(key);

                // Only from here is interrupting this thread safe, so only from here does cancelling do it.
                future.setPoller(Thread.currentThread());

                while ("PENDING".equals(status) && !future.isCancelled()) {

                    try {
                        Thread.sleep(Math.min(10_000L, 100L * counter.getAndIncrement()));
                    } catch (InterruptedException cause) {
                        if (future.isCancelled()) {
                            return;
                        }
                        throw new RuntimeException(cause);
                    }

                    if (future.isCancelled()) {
                        return;
                    }

                    // An IOException – the service could not be reached, or no longer knows the solve – ends
                    // the Future exceptionally, below. Resubmitting is the caller's decision, not this one's.
                    try {
                        response = OptClientV1.parseResponse(myClient.pollResult(key));
                    } catch (InterruptedException cause) {
                        if (future.isCancelled()) {
                            return;
                        }
                        throw cause;
                    }
                    status = (String) response.get(OptClientV1.STATUS);
                }

                if (future.isCancelled()) {
                    return;
                }

                if (!"DONE".equals(status)) {
                    throw new IOException("Unexpected answer from the service about " + key + ": " + response);
                }

                OptResult result = this.handleResult(response);
                future.complete(result);

            } catch (Exception cause) {
                future.completeExceptionally(cause);
            } finally {
                future.setPoller(null);
                // Cancelling interrupted this thread to wake it. Clearing the flag keeps it usable for the
                // next solve the pool hands it.
                Thread.interrupted();
            }
        });

        return future;
    }

}
