#!/usr/bin/env python3
"""Generate a deterministic 1,000-record labeled evaluation corpus."""

from __future__ import annotations

import csv
import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "seed"
RANDOM = random.Random(20260919)

FIRST_NAMES = [
    "Ana", "Alicia", "Alexandra", "Benjamin", "Carla", "Darius", "Elena", "Fatima",
    "Gabriel", "Hannah", "Ibrahim", "Jasmine", "Kai", "Lucia", "Marcus", "Nadia",
    "Owen", "Priya", "Quinn", "Rosa", "Samuel", "Talia", "Uma", "Victor", "Wendy",
]
LAST_NAMES = [
    "Rivera", "Smith", "Johnson", "Patel", "Nguyen", "Garcia", "Brown", "Miller",
    "Davis", "Wilson", "Anderson", "Thomas", "Moore", "Martin", "Lee", "Clark",
    "Lewis", "Walker", "Hall", "Young", "King", "Wright", "Lopez", "Hill", "Green",
]
STREETS = ["Main", "Pine", "Oak", "Cedar", "Lake", "Hill", "Market", "Mission", "Sunset", "Maple"]


def name_for(index: int) -> tuple[str, str, str]:
    first = FIRST_NAMES[index % len(FIRST_NAMES)]
    last = LAST_NAMES[(index * 7) % len(LAST_NAMES)]
    return first, last, f"{first} {last}"


def address_for(index: int) -> tuple[str, str]:
    return f"{100 + index % 700} {STREETS[index % len(STREETS)]} Street", f"{90000 + index % 9000:05d}"


def phone_for(index: int) -> str:
    # Use an assignable NANP central-office code (NXX cannot begin with 0 or 1)
    # so libphonenumber treats the synthetic values as valid US numbers.
    exchange = 200 + (index // 10_000) % 700
    return f"+1415{exchange:03d}{index % 10_000:04d}"


def item(source: str, key: str, name: str, email: str | None, phone: str | None,
         address: str | None, postal: str | None) -> dict[str, object]:
    return {
        "sourceRecordId": key,
        "fullName": name,
        "email": email,
        "phone": phone,
        "address": address,
        "postalCode": postal,
        "updatedAt": "2026-09-01T12:00:00Z",
    }


def typo(name: str) -> str:
    if len(name) < 5:
        return name
    position = next((i for i, c in enumerate(name) if i > 1 and c.lower() in "aeiou"), 2)
    return name[:position] + name[position + 1:]


def main() -> None:
    OUT.mkdir(exist_ok=True)
    records: dict[str, list[dict[str, object]]] = {"CRM": [], "Billing": []}
    labels: list[tuple[str, str, str, str]] = []

    # 400 known duplicate pairs. Variants exercise typos, missing fields, formatting,
    # and conservative handling of semantically changed phones.
    for index in range(400):
        first, last, name = name_for(index)
        address, postal = address_for(index)
        email = f"person{index:04d}@example.test"
        phone = phone_for(index)
        crm_key = f"crm-{index:04d}"
        billing_key = f"billing-{index:04d}"
        crm = item("CRM", crm_key, name, email, phone, address, postal)
        billing_name = typo(name) if index % 9 == 0 else name
        billing_phone = f"(415) {phone[-7:-4]}-{phone[-4:]}" if index % 7 == 0 else phone
        billing_email = None if index % 13 == 0 else email.upper()
        if index % 37 == 0:
            billing_phone = phone_for(index + 5000)  # deliberate changed-phone duplicate
        billing = item("Billing", billing_key, billing_name, billing_email, billing_phone,
                       address.replace(" Street", " St"), postal)
        records["CRM"].append(crm)
        records["Billing"].append(billing)
        group = f"duplicate-{index:04d}"
        scenario = "changed_phone" if index % 37 == 0 else "duplicate"
        labels.extend([(group, "CRM", crm_key, scenario), (group, "Billing", billing_key, scenario)])

    # 100 unique records per source. Corresponding indices deliberately share a
    # household address and surname but have different email and phone identifiers.
    for index in range(100):
        base = 1000 + index
        first, last, name = name_for(base)
        address, postal = address_for(base)
        crm_key = f"crm-unique-{index:03d}"
        billing_key = f"billing-unique-{index:03d}"
        crm = item("CRM", crm_key, name, f"crm.unique{index}@example.test", phone_for(base), address, postal)
        other_first = FIRST_NAMES[(base + 3) % len(FIRST_NAMES)]
        billing = item("Billing", billing_key, f"{other_first} {last}",
                       f"billing.unique{index}@example.test", phone_for(base + 7000), address, postal)
        if index % 10 == 0:
            crm["email"] = None
        if index % 11 == 0:
            billing["phone"] = None
        records["CRM"].append(crm)
        records["Billing"].append(billing)
        labels.extend([
            (f"unique-crm-{index:03d}", "CRM", crm_key, "household_false_positive_trap"),
            (f"unique-billing-{index:03d}", "Billing", billing_key, "household_false_positive_trap"),
        ])

    for source, values in records.items():
        (OUT / f"{source.lower()}.json").write_text(
            json.dumps({"records": values}, indent=2) + "\n", encoding="utf-8"
        )
    with (OUT / "labels.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["group_id", "source_system", "source_record_id", "scenario"])
        writer.writerows(labels)
    print(f"Generated {sum(map(len, records.values()))} records and {len(labels)} labels in {OUT}")


if __name__ == "__main__":
    main()
