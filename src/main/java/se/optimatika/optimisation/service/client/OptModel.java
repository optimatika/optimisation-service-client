/*
 * Copyright 1997-2023 Optimatika
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package se.optimatika.optimisation.service.client;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.ojalgo.concurrent.DaemonPoolExecutor;
import org.ojalgo.function.constant.BigMath;
import org.ojalgo.netio.ASCII;
import org.ojalgo.netio.InMemoryFile;
import org.ojalgo.netio.ResourceLocator.Method;
import org.ojalgo.netio.ServiceClient;
import org.ojalgo.netio.ServiceClient.Request;
import org.ojalgo.netio.ServiceClient.Session;
import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.ExpressionsBasedModel.FileFormat;
import org.ojalgo.optimisation.Optimisation.Result;
import org.ojalgo.optimisation.Optimisation.Sense;
import org.ojalgo.optimisation.Optimisation.State;
import org.ojalgo.optimisation.Variable;
import org.ojalgo.optimisation.service.OptimisationService;
import org.ojalgo.type.context.NumberContext;

public final class OptModel {

    @FunctionalInterface
    public interface BinaryReceiver {

        void receive(boolean value);

    }

    @FunctionalInterface
    public interface IntegerReceiver {

        void receive(int value);

    }

    @FunctionalInterface
    public interface RealReceiver {

        void receive(BigDecimal value);

    }

    private static final ExecutorService EXECUTOR = DaemonPoolExecutor.newCachedThreadPool("opt-serv");

    private static OptimisationService.Integration INTEGRATION = null;

    private static final NumberContext REAL = NumberContext.of(12);

    public static void configure(final URI host) {

        ExpressionsBasedModel.clearIntegrations();

        INTEGRATION = OptimisationService.newIntegration(host.toASCIIString());

        ExpressionsBasedModel.addIntegration(INTEGRATION);
    }

    public static boolean isServiceAvailable() {
        return INTEGRATION != null && INTEGRATION.test();
    }

    private static String name() {
        return ASCII.generateRandom(8, ASCII::isAlphanumeric);
    }

    static boolean booleanValue(final BigDecimal value) {
        return value != null && value.compareTo(BigMath.HALF) > 0;
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

    static OptModel parse(final File file) {

        ExpressionsBasedModel ebm = ExpressionsBasedModel.parse(file);

        return new OptModel(ebm);
    }

    static OptModel parse(final InputStream input, final FileFormat format) {

        ExpressionsBasedModel ebm = ExpressionsBasedModel.parse(input, format);

        return new OptModel(ebm);
    }

    static short shortValue(final BigDecimal value) {
        if (value != null) {
            return value.setScale(0, RoundingMode.HALF_EVEN).shortValue();
        }
        return 0;
    }

    static BigDecimal value(final boolean value) {
        return value ? BigMath.ONE : BigMath.ZERO;
    }

    static BigDecimal value(final double value) {
        return REAL.toBigDecimal(value);
    }

    static BigDecimal value(final long value) {
        return BigDecimal.valueOf(value);
    }

    private final Map<String, Consumer<BigDecimal>> myConsumers = new HashMap<>();
    private final ExpressionsBasedModel myDelegate;
    private final OptObjective myObjective;

    public OptModel() {
        this(new ExpressionsBasedModel());
    }

    OptModel(final ExpressionsBasedModel delegate) {
        super();
        myDelegate = delegate;
        myObjective = new OptObjective(delegate);
    }

    public Future<OptResult> maximise() {
        return this.optimise(Sense.MAX);
    }

    public Future<OptResult> minimise() {
        return this.optimise(Sense.MIN);
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
        return new OptConstraint(myDelegate.newExpression(name));
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

    public OptObjective objective() {
        return myObjective;
    }

    @Override
    public String toString() {
        return myDelegate.toString();
    }

    private OptVariable.BinaryVariable doNewBinary(final String name, final BinaryReceiver valueReceiver) {
        Objects.requireNonNull(name);
        if (valueReceiver != null) {
            myConsumers.put(name, value -> valueReceiver.receive(OptModel.booleanValue(value)));
        }
        return new OptVariable.BinaryVariable(myDelegate.newVariable(name));
    }

    private OptVariable.IntegerVariable doNewInteger(final String name, final IntegerReceiver valueReceiver) {
        Objects.requireNonNull(name);
        if (valueReceiver != null) {
            myConsumers.put(name, value -> valueReceiver.receive(OptModel.intValue(value)));
        }
        return new OptVariable.IntegerVariable(myDelegate.newVariable(name));
    }

    private OptVariable.RealVariable doNewReal(final String name, final RealReceiver valueReceiver) {
        Objects.requireNonNull(name);
        if (valueReceiver != null) {
            myConsumers.put(name, value -> valueReceiver.receive(value));
        }
        return new OptVariable.RealVariable(myDelegate.newVariable(name));
    }

    private OptResult handle(final Result result) {

        State state = result.getState();
        boolean feasible = state.isFeasible();
        boolean optimal = state.isFeasible();
        BigDecimal value = REAL.toBigDecimal(result.getValue());

        if (feasible && result.size() == myDelegate.countVariables()) {
            for (int i = 0, limit = result.size(); i < limit; i++) {
                Variable variable = myDelegate.getVariable(i);
                BigDecimal value2 = result.get(i);
                variable.setValue(value2);
                String name = variable.getName();
                Consumer<BigDecimal> consumer = myConsumers.get(name);
                if (consumer != null) {
                    consumer.accept(value2);
                }
            }
        }

        return new OptResult(feasible, optimal, value);
    }

    Future<OptResult> optimise(final Sense sense) {

        AtomicLong counter = new AtomicLong();

        CompletableFuture<OptResult> future = new CompletableFuture<>();

        EXECUTOR.execute(() -> {

            InMemoryFile file = new InMemoryFile();

            Session session = ServiceClient.newSession();

            String url = "http://localhost:8080/optimisation/v01/put-on-queue/EBM/" + sense.name();

            Request request = session.newRequest(url).header("Accept", "application/json");

            myDelegate.simplify().writeTo(file);
            request.method(Method.POST).body(file.getContentsAsByteArray());

            String response = request.send(BodyHandlers.ofString()).getBody();
            String key = this.parseKey(response);
            String status = this.parseStatus(response);

            while ("PENDING".equals(status)) {

                try {
                    Thread.sleep(1000L * counter.getAndIncrement());
                } catch (InterruptedException cause) {
                    throw new RuntimeException(cause);
                }

                String url2 = "http://localhost:8080/optimisation/v01/poll-result/" + key;

                Request request2 = session.newRequest(url2).method(Method.GET).header("Accept", "application/json");

                response = request2.send(BodyHandlers.ofString()).getBody();

                status = this.parseStatus(response);
            }

            String result2 = this.parseResult(response);
            Result result = Result.parse(result2);
            OptResult handle = this.handle(result);
            future.complete(handle);
        });

        return future;
    }

    String parseKey(final String response) {
        int beginIndex = response.indexOf("key") + 6;
        int endIndex = response.indexOf("\"", beginIndex);
        return response.substring(beginIndex, endIndex);
    }

    String parseResult(final String response) {
        int beginIndex = response.indexOf("result") + 9;
        int endIndex = response.indexOf("\"", beginIndex);
        return response.substring(beginIndex, endIndex);
    }

    String parseStatus(final String response) {
        int beginIndex = response.indexOf("status") + 9;
        int endIndex = response.indexOf("\"", beginIndex);
        return response.substring(beginIndex, endIndex);
    }

}
