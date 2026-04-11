"""
Bulk test data generator for the batch-rating-project.

Populates three MySQL databases:
  - customers_db.customers
  - accounts_db.accounts
  - transactions_db.transactions

Usage:
    python generate.py --accounts 100000
    python generate.py --accounts 50000000 --host localhost --port 3307
"""

import argparse
import csv
import os
import random
import sys
import tempfile
import time
import uuid
from datetime import datetime, timedelta
from decimal import Decimal

try:
    import mysql.connector
    from tqdm import tqdm
except ImportError:
    print("Missing dependencies. Run: pip install mysql-connector-python tqdm")
    sys.exit(1)

# ---------------------------------------------------------------------------
# Reference data
# ---------------------------------------------------------------------------

FIRST_NAMES = [
    "Anna", "Ben", "Clara", "David", "Elena", "Frank", "Greta", "Hans",
    "Ingrid", "Jonas", "Katja", "Leon", "Maria", "Niklas", "Olga", "Peter",
    "Qin", "Rosa", "Stefan", "Theresa", "Uwe", "Vera", "Walter", "Xenia",
    "Yuki", "Zoe", "Andreas", "Brigitte", "Christian", "Diana", "Emil",
    "Franziska", "Georg", "Heike", "Ingo", "Jana", "Klaus", "Laura",
    "Michael", "Nadine", "Oliver", "Paula", "Ralf", "Sabine", "Thomas",
    "Ute", "Viktor", "Wanda", "Xavier", "Yvonne",
]

LAST_NAMES = [
    "Müller", "Schmidt", "Schneider", "Fischer", "Weber", "Meyer", "Wagner",
    "Becker", "Schulz", "Hoffmann", "Schäfer", "Koch", "Bauer", "Richter",
    "Klein", "Wolf", "Schröder", "Neumann", "Schwarz", "Zimmermann",
    "Braun", "Krüger", "Hofmann", "Hartmann", "Lange", "Schmitt", "Werner",
    "Schmitz", "Krause", "Meier", "Lehmann", "Schmid", "Schulze", "Maier",
    "Köhler", "Herrmann", "König", "Walter", "Mayer", "Huber", "Kaiser",
    "Fuchs", "Peters", "Lang", "Scholz", "Möller", "Weiß", "Jung",
    "Hahn", "Schubert", "Vogel", "Friedrich", "Keller", "Günther", "Frank",
    "Berger", "Winkler", "Roth", "Beck", "Lorenz", "Baumann", "Franke",
    "Albrecht", "Schreiber", "Engel", "Voigt", "Sauer", "Arnold", "Haas",
    "Graf", "Kühn", "Brandt", "Kramer", "Simon", "Dietrich", "Martin",
    "Horn", "Busch", "Böhm", "Ziegler", "Fiedler", "Stein", "Sommer",
]

EMAIL_DOMAINS = ["gmail.com", "yahoo.com", "outlook.com", "bank-mail.de", "web.de"]

TRANSACTION_DESCRIPTIONS = {
    "CREDIT":       ["Salary payment", "Bonus transfer", "Freelance income", "Refund received", "Interest earned"],
    "DEBIT":        ["Grocery store", "Online shopping", "Restaurant payment", "Supermarket", "Pharmacy"],
    "TRANSFER_IN":  ["Transfer received", "Payment from friend", "Internal transfer in"],
    "TRANSFER_OUT": ["Transfer sent", "Payment to friend", "Internal transfer out"],
    "FEE":          ["Monthly account fee", "ATM withdrawal fee", "International transfer fee", "Card fee"],
}

CUSTOMER_STATUS_WEIGHTS = [("ACTIVE", 85), ("INACTIVE", 8), ("BLOCKED", 5), ("CLOSED", 2)]
ACCOUNT_TYPE_WEIGHTS    = [("CHECKING", 40), ("SAVINGS", 30), ("CREDIT", 20), ("DEPOSIT", 10)]
ACCOUNT_STATUS_WEIGHTS  = [("ACTIVE", 90), ("FROZEN", 7), ("CLOSED", 3)]
TX_TYPE_WEIGHTS         = [("CREDIT", 35), ("DEBIT", 35), ("TRANSFER_IN", 10), ("TRANSFER_OUT", 10), ("FEE", 10)]


def weighted_choice(choices):
    """choices: list of (value, weight) tuples"""
    values, weights = zip(*choices)
    return random.choices(values, weights=weights, k=1)[0]


# ---------------------------------------------------------------------------
# Connection helpers
# ---------------------------------------------------------------------------

def make_connection(host, port, user, password, database):
    return mysql.connector.connect(
        host=host, port=port, user=user, password=password,
        database=database, allow_local_infile=True,
        connection_timeout=10,
    )


def test_connection(host, port, user, password):
    try:
        conn = mysql.connector.connect(
            host=host, port=port, user=user, password=password,
            connection_timeout=10,
        )
        conn.close()
        return True
    except Exception as exc:
        print(f"\nERROR: Cannot connect to MySQL at {host}:{port} as '{user}'")
        print(f"       {exc}")
        print("\nMake sure Docker Compose is running:  cd services && docker compose up -d")
        return False


# ---------------------------------------------------------------------------
# Bulk loader
# ---------------------------------------------------------------------------

class BulkLoader:
    """Writes rows to a temp CSV then LOAD DATA LOCAL INFILE, falls back to executemany."""

    CHUNK = 100_000

    def __init__(self, conn, table, columns):
        self.conn    = conn
        self.table   = table
        self.columns = columns
        self.cursor  = conn.cursor()
        self._use_load_data = self._check_load_data()

    def _check_load_data(self):
        try:
            self.cursor.execute("SELECT @@global.local_infile")
            val = self.cursor.fetchone()[0]
            return val == 1
        except Exception:
            return False

    def load(self, row_iter, total, desc):
        """Load rows from an iterator with a progress bar."""
        chunk = []
        loaded = 0
        with tqdm(total=total, desc=desc, unit="rows", unit_scale=True) as bar:
            for row in row_iter:
                chunk.append(row)
                if len(chunk) >= self.CHUNK:
                    self._flush(chunk)
                    bar.update(len(chunk))
                    loaded += len(chunk)
                    chunk = []
            if chunk:
                self._flush(chunk)
                bar.update(len(chunk))
                loaded += len(chunk)
        self.conn.commit()
        return loaded

    def _flush(self, rows):
        if self._use_load_data:
            self._flush_local_infile(rows)
        else:
            self._flush_executemany(rows)

    def _flush_local_infile(self, rows):
        with tempfile.NamedTemporaryFile(mode="w", suffix=".csv", delete=False,
                                         newline="", encoding="utf-8") as f:
            fname = f.name
            writer = csv.writer(f, quoting=csv.QUOTE_MINIMAL)
            for row in rows:
                writer.writerow([r if r is not None else r"\\N" for r in row])
        try:
            cols = ", ".join(self.columns)
            sql  = (
                f"LOAD DATA LOCAL INFILE '{fname.replace(os.sep, '/')}' "
                f"INTO TABLE {self.table} "
                f"FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '\"' "
                f"LINES TERMINATED BY '\\n' "
                f"({cols})"
            )
            self.cursor.execute(sql)
        finally:
            os.unlink(fname)

    def _flush_executemany(self, rows):
        placeholders = ", ".join(["%s"] * len(self.columns))
        cols = ", ".join(self.columns)
        sql  = f"INSERT INTO {self.table} ({cols}) VALUES ({placeholders})"
        self.cursor.executemany(sql, rows)


# ---------------------------------------------------------------------------
# Data generators
# ---------------------------------------------------------------------------

def now_str():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S.000")


def random_past_datetime(days_ago_min=1, days_ago_max=365 * 5):
    delta = timedelta(days=random.randint(days_ago_min, days_ago_max),
                      seconds=random.randint(0, 86399))
    return (datetime.now() - delta).strftime("%Y-%m-%d %H:%M:%S.000")


def random_date(year_min=1950, year_max=2000):
    start = datetime(year_min, 1, 1)
    end   = datetime(year_max, 12, 31)
    return (start + timedelta(days=random.randint(0, (end - start).days))).strftime("%Y-%m-%d")


def generate_customers(n_customers):
    """Yields customer rows."""
    used_emails = set()
    for i in range(1, n_customers + 1):
        first = random.choice(FIRST_NAMES)
        last  = random.choice(LAST_NAMES)
        # Guarantee uniqueness: use index suffix
        domain = random.choice(EMAIL_DOMAINS)
        email  = f"{first.lower()}.{last.lower()}{i:06d}@{domain}"
        phone  = f"+49{random.randint(100_000_000, 999_999_999)}" if random.random() < 0.8 else None
        dob    = random_date(1950, 2000)
        status = weighted_choice(CUSTOMER_STATUS_WEIGHTS)
        ts     = random_past_datetime()
        yield (first, last, email, phone, dob, status, ts, ts)


def generate_accounts(customer_ids, account_count):
    """Yields account rows."""
    account_num = 1
    for cust_id in customer_ids:
        num_accounts = random.choices([1, 2, 3], weights=[70, 20, 10], k=1)[0]
        for _ in range(num_accounts):
            if account_num > account_count:
                return
            acc_number   = f"DE{account_num:018d}"
            acc_type     = weighted_choice(ACCOUNT_TYPE_WEIGHTS)
            status       = weighted_choice(ACCOUNT_STATUS_WEIGHTS)
            currency     = "EUR"
            balance      = round(random.uniform(-5000, 100000), 4)
            credit_limit = round(random.uniform(1000, 50000), 4) if acc_type == "CREDIT" else None
            open_date    = (datetime.now() - timedelta(days=random.randint(1, 3650))).strftime("%Y-%m-%d")
            close_date   = None
            if status == "CLOSED":
                open_dt    = datetime.strptime(open_date, "%Y-%m-%d")
                close_date = (open_dt + timedelta(days=random.randint(1, 1000))).strftime("%Y-%m-%d")
            ts = random_past_datetime()
            yield (acc_number, cust_id, acc_type, status, currency,
                   balance, credit_limit, open_date, close_date, ts, ts)
            account_num += 1


def generate_transactions(accounts):
    """Yields transaction rows. accounts: list of (account_id, customer_id, balance)."""
    now   = datetime.now()
    start = now - timedelta(days=120)

    for acc_id, cust_id, balance in accounts:
        n_tx        = random.randint(1, 120)
        running_bal = float(balance)

        for _ in range(n_tx):
            ref_num  = str(uuid.uuid4())
            tx_type  = weighted_choice(TX_TYPE_WEIGHTS)
            amount   = round(random.uniform(1.0, 10000.0), 4)
            if tx_type in ("DEBIT", "TRANSFER_OUT", "FEE"):
                running_bal -= amount
            else:
                running_bal += amount
            bal_after = round(running_bal, 4)

            descriptions = TRANSACTION_DESCRIPTIONS[tx_type]
            description  = random.choice(descriptions) if random.random() < 0.7 else None

            tx_date = start + timedelta(seconds=random.randint(0, int((now - start).total_seconds())))
            tx_date_str = tx_date.strftime("%Y-%m-%d %H:%M:%S.000")
            ts          = tx_date_str  # created_at same as transaction_date

            yield (ref_num, acc_id, cust_id, tx_type, amount,
                   "EUR", bal_after, description, tx_date_str, ts)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Bulk test data generator for batch-rating-project")
    parser.add_argument("--accounts",  type=int, required=True,         help="Number of account records to generate")
    parser.add_argument("--host",      default="localhost",              help="MySQL host (default: localhost)")
    parser.add_argument("--port",      type=int, default=3307,          help="MySQL port (default: 3307)")
    parser.add_argument("--user",      default="appuser",               help="MySQL user (default: appuser)")
    parser.add_argument("--password",  default="apppassword",           help="MySQL password (default: apppassword)")
    args = parser.parse_args()

    n_accounts = args.accounts

    # Estimate counts
    avg_accounts_per_customer = 1 * 0.70 + 2 * 0.20 + 3 * 0.10  # = 1.40
    n_customers = max(1, int(n_accounts / avg_accounts_per_customer))
    avg_tx_per_account = (1 + 120) / 2  # = 60.5
    n_transactions_est = int(n_accounts * avg_tx_per_account)

    print("=" * 60)
    print("  Batch-Rating Test Data Generator")
    print("=" * 60)
    print(f"  Target:        {n_accounts:,} accounts")
    print(f"  Est. customers:{n_customers:,}")
    print(f"  Est. tx:       {n_transactions_est:,}")
    print(f"  MySQL:         {args.host}:{args.port}  user={args.user}")
    print("=" * 60)

    if n_accounts > 1_000_000:
        answer = input(f"\nThis will generate ~{n_customers:,} customers, {n_accounts:,} accounts, "
                       f"~{n_transactions_est:,} transactions.\nContinue? [y/N] ").strip().lower()
        if answer != "y":
            print("Aborted.")
            sys.exit(0)

    if not test_connection(args.host, args.port, args.user, args.password):
        sys.exit(1)

    t0 = time.time()

    # --- Connect to all three DBs ---
    conn_cust = make_connection(args.host, args.port, args.user, args.password, "customers_db")
    conn_acc  = make_connection(args.host, args.port, args.user, args.password, "accounts_db")
    conn_tx   = make_connection(args.host, args.port, args.user, args.password, "transactions_db")

    cur_cust = conn_cust.cursor()
    cur_acc  = conn_acc.cursor()
    cur_tx   = conn_tx.cursor()

    # --- Truncate (reverse FK dependency order) ---
    print("\n[1/4] Truncating tables...")
    cur_tx.execute("SET FOREIGN_KEY_CHECKS=0")
    cur_tx.execute("TRUNCATE TABLE transactions")
    cur_tx.execute("SET FOREIGN_KEY_CHECKS=1")
    conn_tx.commit()

    cur_acc.execute("SET FOREIGN_KEY_CHECKS=0")
    cur_acc.execute("TRUNCATE TABLE accounts")
    cur_acc.execute("SET FOREIGN_KEY_CHECKS=1")
    conn_acc.commit()

    cur_cust.execute("SET FOREIGN_KEY_CHECKS=0")
    cur_cust.execute("TRUNCATE TABLE customers")
    cur_cust.execute("SET FOREIGN_KEY_CHECKS=1")
    conn_cust.commit()

    # --- Generate customers ---
    print("\n[2/4] Generating customers...")
    loader_cust = BulkLoader(conn_cust, "customers",
        ["first_name", "last_name", "email", "phone", "date_of_birth",
         "status", "created_at", "updated_at"])
    loader_cust.load(generate_customers(n_customers), n_customers, "Customers")

    # Fetch generated customer IDs
    cur_cust.execute("SELECT id FROM customers ORDER BY id")
    customer_ids = [row[0] for row in cur_cust.fetchall()]
    print(f"    Loaded {len(customer_ids):,} customers")

    # --- Generate accounts ---
    print("\n[3/4] Generating accounts...")
    loader_acc = BulkLoader(conn_acc, "accounts",
        ["account_number", "customer_id", "account_type", "status", "currency",
         "balance", "credit_limit", "open_date", "close_date", "created_at", "updated_at"])
    loader_acc.load(generate_accounts(customer_ids, n_accounts), n_accounts, "Accounts")

    # Fetch generated account IDs + customer_id + balance for transactions
    cur_acc.execute("SELECT id, customer_id, balance FROM accounts ORDER BY id")
    accounts = cur_acc.fetchall()
    print(f"    Loaded {len(accounts):,} accounts")

    # --- Generate transactions ---
    print("\n[4/4] Generating transactions...")
    loader_tx = BulkLoader(conn_tx, "transactions",
        ["reference_number", "account_id", "customer_id", "transaction_type",
         "amount", "currency", "balance_after", "description", "transaction_date", "created_at"])
    n_tx = loader_tx.load(generate_transactions(accounts), n_transactions_est, "Transactions")

    # --- Summary ---
    elapsed = time.time() - t0
    cur_cust.execute("SELECT COUNT(*) FROM customers")
    real_customers = cur_cust.fetchone()[0]
    cur_acc.execute("SELECT COUNT(*) FROM accounts")
    real_accounts = cur_acc.fetchone()[0]
    cur_tx.execute("SELECT COUNT(*) FROM transactions")
    real_tx = cur_tx.fetchone()[0]

    print("\n" + "=" * 60)
    print("  Done!")
    print(f"  Customers:    {real_customers:>12,}")
    print(f"  Accounts:     {real_accounts:>12,}")
    print(f"  Transactions: {real_tx:>12,}")
    print(f"  Time:         {elapsed:>10.1f}s  ({elapsed/60:.1f}m)")
    print("=" * 60)

    for conn in (conn_cust, conn_acc, conn_tx):
        conn.close()


if __name__ == "__main__":
    main()
