# Test Data Generator

Bulk populates three MySQL databases with realistic banking test data.

## Prerequisites

- Python 3.9+
- MySQL running (via Docker Compose or Kubernetes port-forward)

```bash
pip install -r requirements.txt
```

## Quick Start

```bash
# Start databases first
cd services && docker compose up -d

# Generate 100,000 accounts (~70,000 customers, ~6M transactions)
python generate.py --accounts 100000

# Large dataset: 1 million accounts
python generate.py --accounts 1000000
```

## Connection Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `--host` | `localhost` | MySQL host |
| `--port` | `3307` | MySQL port (matches docker-compose.yml) |
| `--user` | `appuser` | MySQL user |
| `--password` | `apppassword` | MySQL password |
| `--accounts` | required | Number of account records to generate |

## Data Distribution

| Table | Distribution |
|-------|-------------|
| **customers** | 1 account: 70%, 2 accounts: 20%, 3 accounts: 10% |
| **accounts** | CHECKING 40%, SAVINGS 30%, CREDIT 20%, DEPOSIT 10% |
| **transactions** | 1-120 per account, spread over last 4 months |

## Performance Notes

- Uses `LOAD DATA LOCAL INFILE` when available (fastest) — requires `local_infile=ON` in MySQL
- Falls back to `executemany()` in batches of 100,000 rows
- Expected throughput: ~500K rows/min on localhost

| Accounts | Est. Customers | Est. Transactions | Est. Time |
|----------|---------------|-------------------|-----------|
| 100,000 | ~71,000 | ~6M | ~1 min |
| 1,000,000 | ~714,000 | ~60M | ~10 min |
| 10,000,000 | ~7.1M | ~600M | ~2h |

For 50M accounts, run overnight. A confirmation prompt is shown for datasets > 1M accounts.

## Warning

The script **truncates all three tables** before generating new data. All existing data will be lost.
