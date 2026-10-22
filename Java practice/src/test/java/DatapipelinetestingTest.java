
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatapipelinetestingTest {

    @Test
    void removesDuplicateTransactions() {
        Datapipelinetesting.Transaction first =
                transaction("tx-1", "Coffee Shop", 5.00);

        Datapipelinetesting.Transaction duplicate =
                transaction("tx-1", "Coffee Shop", 5.00);

        List<Datapipelinetesting.Transaction> input =
                List.of(first, duplicate);

        Datapipelinetesting.CleanResult result =
                Datapipelinetesting.cleanTransactions(input);

        assertEquals(1, result.transactions.size());
        assertEquals(1, result.duplicates);
        assertEquals(0, result.invalid);
    }

    @Test
    void rejectsTransactionsWithMissingRequiredFields() {
        Datapipelinetesting.Transaction invalid =
                transaction("", "Coffee Shop", 5.00);

        Datapipelinetesting.CleanResult result =
                Datapipelinetesting.cleanTransactions(
                        List.of(invalid));

        assertEquals(0, result.transactions.size());
        assertEquals(1, result.invalid);
    }

    @Test
    void rejectsTransactionsWithNonFiniteAmounts() {
        Datapipelinetesting.Transaction invalid =
                transaction("tx-2", "Coffee Shop", Double.NaN);

        Datapipelinetesting.CleanResult result =
                Datapipelinetesting.cleanTransactions(
                        List.of(invalid));

        assertEquals(0, result.transactions.size());
        assertEquals(1, result.invalid);
    }

    @Test
    void trimsTransactionNames() {
        Datapipelinetesting.Transaction item =
                transaction("tx-3", "  Grocery Store  ", 25.00);

        Datapipelinetesting.CleanResult result =
                Datapipelinetesting.cleanTransactions(
                        List.of(item));

        assertEquals(1, result.transactions.size());
        assertEquals("Grocery Store",
                result.transactions.get(0).name);
    }

    @Test
    void calculatesIncomingSpendingAndNet() {
        List<Datapipelinetesting.Transaction> transactions =
                new ArrayList<>();

        transactions.add(transaction("tx-4", "Groceries", 40.00));
        transactions.add(transaction("tx-5", "Paycheque", -100.00));
        transactions.add(transaction("tx-6", "Transit", 10.00));

        Datapipelinetesting.Analytics result =
                Datapipelinetesting.analyze(transactions);

        assertEquals(3, result.count);
        assertEquals(50.00, result.spending, 0.001);
        assertEquals(100.00, result.incoming, 0.001);
        assertEquals(-50.00, result.net, 0.001);
    }

    @Test
    void groupsTransactionsByCurrency() {
        List<Datapipelinetesting.Transaction> transactions =
                List.of(
                        transaction("tx-7", "Coffee", 5.00, "CAD"),
                        transaction("tx-8", "Lunch", 15.00, "CAD"),
                        transaction("tx-9", "Book", 20.00, "USD")
                );

        Datapipelinetesting.Analytics result =
                Datapipelinetesting.analyze(transactions);

        assertEquals(3, result.count);
        assertEquals(2, result.countByCurrency.get("CAD"));
        assertEquals(1, result.countByCurrency.get("USD"));
        assertEquals(20.00,
                result.spendingByCurrency.get("CAD"), 0.001);
        assertEquals(20.00,
                result.spendingByCurrency.get("USD"), 0.001);
    }

    private Datapipelinetesting.Transaction transaction(
            String id, String name, double amount) {
        return transaction(id, name, amount, "CAD");
    }

    private Datapipelinetesting.Transaction transaction(
            String id, String name, double amount, String currency) {

        Datapipelinetesting.Transaction transaction =
                new Datapipelinetesting.Transaction();

        transaction.id = id;
        transaction.date = "2026-10-22";
        transaction.name = name;
        transaction.currency = currency;
        transaction.amount = amount;

        return transaction;
    }
}
