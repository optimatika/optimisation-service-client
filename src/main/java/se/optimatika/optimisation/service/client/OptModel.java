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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Builder for optimisation models that are solved remotely via optimisation-service-server.
 * <p>
 * Use the factory methods to create variables ({@link #newRealVariable}, {@link #newIntegerVariable},
 * {@link #newBinaryVariable}), define constraints ({@link #newConstraint}), and set the objective function
 * ({@link #objective()}). Then call {@link #maximise()} or {@link #minimise()} to submit the model to the
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
     * Submits this model to the server for maximisation and returns a {@link Future} that completes with the
     * {@link OptResult}. On completion, solution values are written back to the variables.
     */
    public Future<OptResult> maximise() {
        return this.optimise(true);
    }

    /**
     * Submits this model to the server for minimisation and returns a {@link Future} that completes with the
     * {@link OptResult}. On completion, solution values are written back to the variables.
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
     * Returns this model's objective function. Each model has exactly one objective.
     */
    public OptObjective objective() {
        return myObjective;
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
        CompletableFuture<OptResult> future = new CompletableFuture<>();

        EXECUTOR.execute(() -> {

            try {

                Map<String, Object> response = myClient.putOnQueueParsed(this.toBytesOfEBM(), "EBM", maximize);
                String key = (String) response.get(OptClientV1.KEY);
                String status = (String) response.get(OptClientV1.STATUS);

                while ("PENDING".equals(status)) {

                    try {
                        Thread.sleep(Math.min(10_000L, 100L * counter.getAndIncrement()));
                    } catch (InterruptedException cause) {
                        throw new RuntimeException(cause);
                    }

                    response = myClient.pollResultParsed(key);
                    status = (String) response.get(OptClientV1.STATUS);
                }

                OptResult result = this.handleResult(response);
                future.complete(result);

            } catch (Exception cause) {
                future.completeExceptionally(cause);
            }
        });

        return future;
    }

}
