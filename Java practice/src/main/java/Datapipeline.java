import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public class Datapipeline {

    private static final String BASE_URL =
            "https://sandbox.plaid.com";

    private static final int BENCHMARK_ITERATIONS = 1000;
    private static final int PAGE_SIZE = 100;

    private static HttpClient client;

    static class Transaction {
        String id;
        String date;
        String name;
        String currency;
        double amount;
    }

    static class CleanResult {
        List<Transaction> transactions = new ArrayList<>();
        int invalid;
        int duplicates;
    }

    static class Analytics {
        int count;
        double spending;
        double incoming;
        double net;
        Map<String, Double> spendingByCurrency =
                new LinkedHashMap<>();
        Map<String, Integer> countByCurrency =
                new LinkedHashMap<>();
    }

    public static void main(String[] args) {
        long programStart = System.nanoTime();

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("====================================");
            System.out.println(" BANKING TRANSACTION DATA PIPELINE");
            System.out.println("====================================");

            System.out.print("Plaid Sandbox client_id: ");
            String clientId = scanner.nextLine().trim();

            System.out.print("Plaid Sandbox secret: ");
            String secret = scanner.nextLine().trim();

            if (clientId.isEmpty() || secret.isEmpty()) {
                throw new IllegalArgumentException(
                        "Both Plaid credentials are required.");
            }

            client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            // Step 1: Create a test banking connection.
            long apiStart = System.nanoTime();

            Map<String, Object> tokenResponse = post(
                    "/sandbox/public_token/create",
                    clientId,
                    secret,
                    "{\"institution_id\":\"ins_109508\","
                    + "\"initial_products\":[\"transactions\"]}");

            String publicToken = requiredString(
                    tokenResponse, "public_token");

            // Step 2: Exchange the temporary token.
            Map<String, Object> exchangeResponse = post(
                    "/item/public_token/exchange",
                    clientId,
                    secret,
                    "{\"public_token\":"
                    + quote(publicToken) + "}");

            String accessToken = requiredString(
                    exchangeResponse, "access_token");

            // Step 3: Fetch the last 90 days of test transactions.
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(90);

            List<Transaction> rawTransactions =
                    fetchTransactions(
                            clientId,
                            secret,
                            accessToken,
                            startDate,
                            endDate);

            double apiTimeMs =
                    (System.nanoTime() - apiStart) / 1_000_000.0;

            System.out.println("\nPlaid Sandbox connection successful.");
            System.out.println("Environment: Sandbox (test data)");
            System.out.println("Date range: " + startDate
                    + " to " + endDate);
            System.out.println("Transactions retrieved: "
                    + rawTransactions.size());
            System.out.printf("API operation time: %.2f ms%n",
                    apiTimeMs);

            // Step 4: Validate and deduplicate transactions.
            long processingStart = System.nanoTime();

            CleanResult cleaned = cleanTransactions(rawTransactions);
            Analytics analytics = analyze(cleaned.transactions);

            double processingTimeMs =
                    (System.nanoTime() - processingStart)
                    / 1_000_000.0;

            // Step 5: Display results.
            printSample(cleaned.transactions);
            printAnalytics(analytics);
            printQualityReport(rawTransactions.size(), cleaned);

            // Step 6: Benchmark local processing only.
            benchmark(rawTransactions);

            double totalTimeMs =
                    (System.nanoTime() - programStart)
                    / 1_000_000.0;

            System.out.println("\n========== PERFORMANCE ==========");
            System.out.printf("API operations: %.2f ms%n", apiTimeMs);
            System.out.printf("Initial processing: %.2f ms%n",
                    processingTimeMs);
            System.out.printf("Total elapsed: %.2f ms%n", totalTimeMs);
            System.out.println(
                    "API timings include Sandbox setup and retrieval.");
            System.out.println("=================================");

        } catch (Exception e) {
            System.err.println("\nPipeline failed: " + e.getMessage());
            System.err.println(
                    "Check your Sandbox credentials, internet "
                    + "connection, and Plaid API response.");
        }
    }

    private static Map<String, Object> post(
            String endpoint,
            String clientId,
            String secret,
            String body) throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + endpoint))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .header("PLAID-CLIENT-ID", clientId)
                .header("PLAID-SECRET", secret)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString());

        Object parsed = Json.parse(response.body());

        if (!(parsed instanceof Map)) {
            throw new RuntimeException(
                    "Plaid returned an unexpected response.");
        }

        Map<String, Object> result = asMap(parsed);

        if (response.statusCode() < 200
                || response.statusCode() >= 300) {
            String code = stringValue(result.get("error_code"));
            String message =
                    stringValue(result.get("error_message"));

            throw new RuntimeException(
                    "Plaid HTTP " + response.statusCode()
                    + (code.isEmpty() ? "" : " [" + code + "]")
                    + (message.isEmpty() ? "" : ": " + message));
        }

        return result;
    }

    private static List<Transaction> fetchTransactions(
            String clientId,
            String secret,
            String accessToken,
            LocalDate startDate,
            LocalDate endDate) throws Exception {

        List<Transaction> all = new ArrayList<>();
        int offset = 0;
        int total = Integer.MAX_VALUE;

        while (offset < total) {
            String body = "{"
                    + "\"access_token\":" + quote(accessToken) + ","
                    + "\"start_date\":" + quote(startDate.toString()) + ","
                    + "\"end_date\":" + quote(endDate.toString()) + ","
                    + "\"options\":{"
                    + "\"count\":" + PAGE_SIZE + ","
                    + "\"offset\":" + offset
                    + "}}";

            Map<String, Object> response = null;

            // Retry if Plaid has not prepared the transactions yet.
            for (int attempt = 1; attempt <= 5; attempt++) {
                try {
                    response = post(
                            "/transactions/get",
                            clientId,
                            secret,
                            body);
                    break;

                } catch (RuntimeException e) {
                    String message = e.getMessage();

                    if (message == null
                            || !message.contains("PRODUCT_NOT_READY")
                            || attempt == 5) {
                        throw e;
                    }

                    System.out.println(
                            "Transactions are not ready yet. Retry "
                            + attempt + "/5...");

                    try {
                        Thread.sleep(attempt * 2000L);

                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();

                        throw new RuntimeException(
                                "Waiting for transactions was interrupted.",
                                interrupted);
                    }
                }
            }

            if (response == null) {
                throw new RuntimeException(
                        "Plaid did not return a transaction response.");
            }

            Object transactionData = response.get("transactions");

            if (!(transactionData instanceof List)) {
                throw new RuntimeException(
                        "Plaid response did not contain transactions.");
            }

            List<?> rows = (List<?>) transactionData;

            for (Object row : rows) {
                if (!(row instanceof Map)) {
                    continue;
                }

                Map<String, Object> item = asMap(row);
                Transaction transaction = new Transaction();

                transaction.id = stringValue(
                        item.get("transaction_id"));
                transaction.date = stringValue(item.get("date"));
                transaction.name = stringValue(item.get("name"));

                String currency =
                        stringValue(item.get("iso_currency_code"));

                if (currency.isEmpty()) {
                    currency = stringValue(
                            item.get("unofficial_currency_code"));
                }

                transaction.currency = currency.isEmpty()
                        ? "UNKNOWN" : currency;

                Object amount = item.get("amount");

                if (!(amount instanceof Number)) {
                    transaction.amount = Double.NaN;
                } else {
                    transaction.amount =
                            ((Number) amount).doubleValue();
                }

                all.add(transaction);
            }

            Object totalValue = response.get("total_transactions");

            if (!(totalValue instanceof Number)) {
                total = all.size();
            } else {
                total = ((Number) totalValue).intValue();
            }

            if (rows.isEmpty()) {
                break;
            }

            offset += rows.size();
        }

        return all;
    }

    private static CleanResult cleanTransactions(
            List<Transaction> input) {

        CleanResult result = new CleanResult();
        Set<String> seen = new HashSet<>();

        for (Transaction transaction : input) {
            if (transaction.id == null
                    || transaction.id.isBlank()
                    || transaction.name == null
                    || transaction.name.isBlank()
                    || transaction.date == null
                    || transaction.date.isBlank()
                    || !Double.isFinite(transaction.amount)
                    || transaction.currency == null
                    || transaction.currency.isBlank()) {
                result.invalid++;
                continue;
            }

            if (!seen.add(transaction.id)) {
                result.duplicates++;
                continue;
            }

            transaction.name = transaction.name.trim();
            result.transactions.add(transaction);
        }

        return result;
    }

    private static Analytics analyze(List<Transaction> transactions) {
        Analytics analytics = new Analytics();

        for (Transaction transaction : transactions) {
            analytics.count++;

            // Plaid convention: positive = money out;
            // negative = money into the account.
            if (transaction.amount > 0) {
                analytics.spending += transaction.amount;
            } else if (transaction.amount < 0) {
                analytics.incoming += -transaction.amount;
            }

            analytics.net += transaction.amount;

            String currency = transaction.currency;

            analytics.spendingByCurrency.put(
                    currency,
                    analytics.spendingByCurrency.getOrDefault(
                            currency, 0.0) + transaction.amount);

            analytics.countByCurrency.put(
                    currency,
                    analytics.countByCurrency.getOrDefault(
                            currency, 0) + 1);
        }

        return analytics;
    }

    private static void printSample(List<Transaction> transactions) {
        System.out.println("\n========== SAMPLE TRANSACTIONS ==========");

        if (transactions.isEmpty()) {
            System.out.println("No valid transactions in this date range.");
            return;
        }

        int limit = Math.min(10, transactions.size());

        for (int i = 0; i < limit; i++) {
            Transaction t = transactions.get(i);

            System.out.printf(
                    "%s | %-3s | %10.2f | %s%n",
                    t.date, t.currency, t.amount, t.name);
        }

        System.out.println("=========================================");
    }

    private static void printAnalytics(Analytics analytics) {
        System.out.println("\n========== FINANCIAL ANALYTICS ==========");
        System.out.println("Valid transactions: " + analytics.count);
        System.out.printf("Total money out: %.2f%n",
                analytics.spending);
        System.out.printf("Total money in:  %.2f%n",
                analytics.incoming);
        System.out.printf("Net amount:      %.2f%n",
                analytics.net);

        System.out.println("\nAmounts grouped by currency:");

        for (String currency : analytics.spendingByCurrency.keySet()) {
            System.out.printf(
                    "%s: net %.2f across %d transactions%n",
                    currency,
                    analytics.spendingByCurrency.get(currency),
                    analytics.countByCurrency.get(currency));
        }

        System.out.println(
                "Note: combined totals across currencies are not converted.");
        System.out.println("=========================================");
    }

    private static void printQualityReport(
            int received, CleanResult cleaned) {

        System.out.println("\n========== DATA QUALITY ==========");
        System.out.println("Records retrieved: " + received);
        System.out.println("Invalid records:   " + cleaned.invalid);
        System.out.println("Duplicate records: " + cleaned.duplicates);
        System.out.println("Records processed: "
                + cleaned.transactions.size());
        System.out.println("==================================");
    }

    private static void benchmark(List<Transaction> input) {
        for (int i = 0; i < 100; i++) {
            analyze(cleanTransactions(input).transactions);
        }

        long start = System.nanoTime();

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            analyze(cleanTransactions(input).transactions);
        }

        double elapsedMs =
                (System.nanoTime() - start) / 1_000_000.0;

        System.out.println("\n========== PROCESSING BENCHMARK ==========");
        System.out.println("Warm-up iterations: 100");
        System.out.println("Measured iterations: " + BENCHMARK_ITERATIONS);
        System.out.printf("Average processing time: %.4f ms%n",
                elapsedMs / BENCHMARK_ITERATIONS);

        double averageSeconds =
                elapsedMs / BENCHMARK_ITERATIONS / 1000.0;

        double throughput = averageSeconds > 0
                ? input.size() / averageSeconds : 0;

        System.out.printf("Approx. throughput: %.0f records/second%n",
                throughput);
        System.out.println("Network calls are excluded from this benchmark.");
        System.out.println("==========================================");
    }

    private static Map<String, Object> asMap(Object value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    private static String requiredString(
            Map<String, Object> map, String key) {

        String value = stringValue(map.get(key));

        if (value.isEmpty()) {
            throw new RuntimeException(
                    "Plaid response is missing field: " + key);
        }

        return value;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;

                default:
                    if (c < 0x20) {
                        result.append(
                                String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
            }
        }

        return result.append('"').toString();
    }

    // Small recursive JSON parser so no extra library is required.
    static class Json {
        private final String input;
        private int position;

        private Json(String input) {
            this.input = input;
        }

        static Object parse(String input) {
            Json parser = new Json(input);
            Object value = parser.parseValue();
            parser.skipWhitespace();

            if (parser.position != input.length()) {
                throw new IllegalArgumentException(
                        "Unexpected characters in JSON response.");
            }

            return value;
        }

        private Object parseValue() {
            skipWhitespace();

            if (position >= input.length()) {
                throw new IllegalArgumentException(
                        "Unexpected end of JSON.");
            }

            char c = input.charAt(position);

            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't') return readLiteral("true", Boolean.TRUE);
            if (c == 'f') return readLiteral("false", Boolean.FALSE);
            if (c == 'n') return readLiteral("null", null);

            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            position++;
            skipWhitespace();

            if (take('}')) return map;

            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');

                map.put(key, parseValue());

                skipWhitespace();

                if (take('}')) return map;

                expect(',');
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            position++;
            skipWhitespace();

            if (take(']')) return list;

            while (true) {
                list.add(parseValue());
                skipWhitespace();

                if (take(']')) return list;

                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder result = new StringBuilder();

            while (position < input.length()) {
                char c = input.charAt(position++);

                if (c == '"') return result.toString();

                if (c == '\\') {
                    if (position >= input.length()) break;

                    char escape = input.charAt(position++);

                    switch (escape) {
                        case '"': result.append('"'); break;
                        case '\\': result.append('\\'); break;
                        case '/': result.append('/'); break;
                        case 'b': result.append('\b'); break;
                        case 'f': result.append('\f'); break;
                        case 'n': result.append('\n'); break;
                        case 'r': result.append('\r'); break;
                        case 't': result.append('\t'); break;

                        case 'u':
                            if (position + 4 > input.length()) {
                                throw new IllegalArgumentException(
                                        "Invalid Unicode escape.");
                            }

                            String hex = input.substring(
                                    position, position + 4);

                            result.append(
                                    (char) Integer.parseInt(hex, 16));

                            position += 4;
                            break;

                        default:
                            throw new IllegalArgumentException(
                                    "Invalid JSON escape.");
                    }
                } else {
                    result.append(c);
                }
            }

            throw new IllegalArgumentException(
                    "Unterminated JSON string.");
        }

        private Object parseNumber() {
            int start = position;

            while (position < input.length()) {
                char c = input.charAt(position);

                if ((c >= '0' && c <= '9') || c == '-'
                        || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    position++;
                } else {
                    break;
                }
            }

            if (start == position) {
                throw new IllegalArgumentException(
                        "Invalid JSON value.");
            }

            String number = input.substring(start, position);

            try {
                return Double.valueOf(number);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid JSON number.");
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!input.startsWith(literal, position)) {
                throw new IllegalArgumentException(
                        "Invalid JSON literal.");
            }

            position += literal.length();
            return value;
        }

        private void skipWhitespace() {
            while (position < input.length()
                    && Character.isWhitespace(input.charAt(position))) {
                position++;
            }
        }

        private boolean take(char expected) {
            if (position < input.length()
                    && input.charAt(position) == expected) {
                position++;
                return true;
            }

            return false;
        }

        private void expect(char expected) {
            skipWhitespace();

            if (!take(expected)) {
                throw new IllegalArgumentException(
                        "Invalid JSON: expected '" + expected + "'.");
            }
        }
    }
}