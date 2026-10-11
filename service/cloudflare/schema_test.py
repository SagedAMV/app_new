"""Local SQLite checks only; no Cloudflare, Android build, or persistent database."""
from pathlib import Path
import re
import sqlite3
import unittest

HERE = Path(__file__).resolve().parent
WORKER = (HERE / "worker.mjs").read_text()
CLAIM_SQL = re.search(r"UPDATE provisioning_invites\s.*?RETURNING token_hash", WORKER, re.S).group(0)
TOKEN = "a" * 64
CLAIM = "b" * 64


class ProvisioningSchemaTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript((HERE / "schema.sql").read_text())
        self.db.execute(
            "INSERT INTO provisioning_invites (token_hash,label,expires_at,enabled) VALUES (?,?,?,?)",
            (TOKEN, "synthetic fixture", 1000, 1),
        )

    def tearDown(self):
        self.db.close()

    def claim(self, digest=CLAIM, expiry=5000, now=100):
        return self.db.execute(CLAIM_SQL, (digest, expiry, TOKEN, now)).fetchone()

    def test_initial_null_claim_is_valid_and_atomic_update_binds_it(self):
        self.assertEqual((None, None), self.db.execute(
            "SELECT claim_hash,claim_expires_at FROM provisioning_invites"
        ).fetchone())
        self.assertEqual((TOKEN,), self.claim())
        self.assertEqual((CLAIM, 5000), self.db.execute(
            "SELECT claim_hash,claim_expires_at FROM provisioning_invites"
        ).fetchone())

    def test_exact_retry_survives_initial_invitation_expiry_but_not_claim_expiry(self):
        self.assertIsNotNone(self.claim())
        self.assertIsNotNone(self.claim(now=1100))
        self.assertIsNone(self.claim(now=5000))

    def test_other_recipient_cannot_take_a_used_invitation(self):
        self.assertIsNotNone(self.claim())
        self.assertIsNone(self.claim(digest="c" * 64))
        self.assertEqual((CLAIM,), self.db.execute("SELECT claim_hash FROM provisioning_invites").fetchone())

    def test_expired_request_does_not_consume_a_valid_invitation(self):
        self.assertIsNone(self.claim(expiry=99, now=100))
        self.assertEqual((None,), self.db.execute("SELECT claim_hash FROM provisioning_invites").fetchone())

    def test_expired_or_disabled_invitation_is_rejected(self):
        self.assertIsNone(self.claim(now=1000))
        self.db.execute("UPDATE provisioning_invites SET enabled=0")
        self.assertIsNone(self.claim())

    def test_partial_claim_and_malformed_hash_violate_constraints(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE provisioning_invites SET claim_hash=?", (CLAIM,))
        for invalid in (None, "not-a-hash", "g" * 64):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute("UPDATE provisioning_invites SET token_hash=?", (invalid,))

    def test_operator_sql_template_matches_actual_schema(self):
        source = (HERE / "issue-invite.mjs").read_text()
        sql = re.search(r"const sql = `(.*?)`;", source, re.S).group(1)
        sql = sql.replace("${tokenHash}", "d" * 64).replace("${expiresAt}", "2000")
        sql = sql.replace("${label.replaceAll(\"'\", \"''\")}", "fixture").replace("\\n", "\n")
        self.assertNotIn("${", sql)
        self.db.execute(sql)
        self.assertEqual(2, self.db.execute("SELECT COUNT(*) FROM provisioning_invites").fetchone()[0])

    def test_schema_has_no_credential_or_password_columns(self):
        columns = {row[1] for row in self.db.execute("PRAGMA table_info(provisioning_invites)")}
        self.assertFalse(columns & {"access_key_id", "secret_access_key", "password", "password_hash", "invitation"})


if __name__ == "__main__":
    unittest.main()
