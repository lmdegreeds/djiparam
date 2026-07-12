#!/usr/bin/env python3
"""Normalize and compare DJI flight-controller parameter exports.

The script treats pipe-separated names as aliases, collapses the v1/v2
duplicates for Air 3 and Air 3S, and writes reproducible CSV/JSON appendices.
"""

from __future__ import annotations

import csv
import json
import math
import re
from collections import Counter, defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parent

PROFILES = {
    "DJI Air 3": "dji air3 dh v2.dhv2params",
    "DJI Air 3S": "dji air3s.dhv2params",
    "DJI Mini 4 Pro": "Mini 4 Pro.dhp",
    "DJI Neo": "Neo.dhp",
    "DJI Neo 2": "Neo 2.dhp",
    "DJI Flip": "Flip.dhp",
    "DJI Avata 360": "Avata 360.dhp",
    "DJI Lito X1": "dh-params-litox1-03.07-clean.dhv2params",
}

DUPLICATE_PAIRS = {
    "DJI Air 3": ("dji air3 dh v1.dhp", "dji air3 dh v2.dhv2params"),
    "DJI Air 3S": ("dji air 3s dhv1.dhp", "dji air3s.dhv2params"),
}


class UnionFind:
    def __init__(self) -> None:
        self.parent: dict[str, str] = {}

    def find(self, item: str) -> str:
        self.parent.setdefault(item, item)
        if self.parent[item] != item:
            self.parent[item] = self.find(self.parent[item])
        return self.parent[item]

    def union(self, left: str, right: str) -> None:
        a, b = self.find(left), self.find(right)
        if a != b:
            self.parent[b] = a


def close(left, right, tolerance: float = 1e-6) -> bool:
    try:
        return math.isclose(
            float(left), float(right), rel_tol=tolerance, abs_tol=tolerance
        )
    except (TypeError, ValueError):
        return left == right


def display(value) -> str:
    if value is None:
        return ""
    try:
        number = float(value)
        if math.isfinite(number):
            if number.is_integer():
                return str(int(number))
            return f"{number:.6g}"
    except (TypeError, ValueError):
        pass
    return str(value)


def read_export(filename: str) -> list[dict]:
    path = ROOT / filename
    payload = json.loads(path.read_text(encoding="utf-8-sig"))
    rows = payload if isinstance(payload, list) else payload["params"]
    normalized = []
    for row in rows:
        if "table_no" in row:
            normalized.append(
                {
                    "raw_name": row["name"],
                    "table": row.get("table_no"),
                    "index": row.get("param_index"),
                    "type": row.get("type_id"),
                    "min": row.get("min"),
                    "max": row.get("max"),
                    "default": row.get("default"),
                    "value": row.get("value"),
                }
            )
            continue

        variants = row.get("data", {})
        data = next(iter(variants.values()), {})
        normalized.append(
            {
                "raw_name": row["name"],
                "table": row.get("table_number"),
                "index": row.get("param_index"),
                "type": row.get("param_type"),
                "min": data.get("limit_min"),
                "max": data.get("limit_max"),
                "default": data.get("default"),
                "value": data.get("value"),
            }
        )
    return normalized


def choose_primary(aliases: set[str]) -> str:
    preferred = sorted(a for a in aliases if a.startswith("g_config."))
    if preferred:
        return preferred[0]
    return sorted(aliases, key=lambda item: (len(item), item))[0]


PER_AIRCRAFT_CALIBRATION_STATE = re.compile(
    r"^(?:compass\d+\.(?:cali_step|status_data)|"
    r"g_config\.fdi_sensor\[\d+\]\.(?:acc_bias|gyr_bias|mag_over|mag_stat)|"
    r"g_status\.all_gyr_acc\.(?:cali_cnt|cali_state|need_cali_type|msc_.*)|"
    r"imu\d+_cali_status\..*|"
    r"imu_app_temp_cali\.(?:cali_cnt|state)|"
    r"imu_cali_[012]\.acc_gyro.*|"
    r"imu_cali_ui\..*|"
    r"device_gyr_acc\.busy\.cali_cnt|"
    r"g_cfg_debug\.imu_cali_state.*|"
    r"mass_center_calibrated)$",
    re.IGNORECASE,
)

MODEL_SENSOR_GEOMETRY = re.compile(
    r"^(?:(?:imu|gps)[0-2]_[xyz]|"
    r"imu[0-2]_mount_[xyz]|imu[0-2]_direction|"
    r"antenna_gps.*_[xyz]|imu_gps_.*_offset_[ab]_[xyz]|"
    r"uwb\d+_[xyz]|lida_[xyz])$",
    re.IGNORECASE,
)

CALIBRATION_CONTROL_OR_RULE = re.compile(
    r"cali|calib|(?:acc|gyr)_fdi_open_bias|mass_center_collecting",
    re.IGNORECASE,
)


def calibration_scope_for(name: str, aliases: set[str]) -> tuple[str, str]:
    text = "|".join(sorted(aliases | {name}))
    if PER_AIRCRAFT_CALIBRATION_STATE.search(text):
        return (
            "per_aircraft_calibration_or_bias_state",
            "Do not copy: per-aircraft runtime/calibration state, not a model preset",
        )
    if MODEL_SENSOR_GEOMETRY.search(text) and not text.lower().startswith("sim_"):
        return (
            "model_sensor_geometry",
            "Do not copy across models; this is installation geometry, not proven per-unit calibration",
        )
    if CALIBRATION_CONTROL_OR_RULE.search(text):
        return (
            "calibration_control_or_health_rule",
            "Calibration command/status gate/health rule; not a calibration coefficient",
        )
    return "", ""


def category_for(name: str, aliases: set[str]) -> str:
    text = "|".join(sorted(aliases | {name})).lower()

    calibration_scope, _ = calibration_scope_for(name, aliases)
    if calibration_scope == "per_aircraft_calibration_or_bias_state":
        return "Per-aircraft calibration & bias state"
    if calibration_scope == "model_sensor_geometry":
        return "Sensor installation geometry (model)"

    rules = [
        (
            "Flight limits & regulation",
            r"eu_ce|remote.?id|(^|[._|])rid|geo|airport|country|license|"
            r"flying_limit|height_limit|max_height|max_radius|radius_limit|"
            r"roof_limit|novice|reg_|c0_rid|limit_height|limit_radius",
        ),
        (
            "RTH, takeoff, landing & failsafe",
            r"go_home|gohome|homing|landing|takeoff|fail_safe|failsafe|"
            r"rc_lost|sdr_lost|homepoint|home_point|prevent_landing",
        ),
        (
            "Obstacle sensing, vision & terrain",
            r"avoid|vision|vps|mvo|tof|lidar|radar|ultrasonic|obstacle|"
            r"ground_detect|terrain|safe_dis",
        ),
        (
            "Battery & power",
            r"battery|(^|[._|])bat|voltage|power|soc_|remain_cap|cell_",
        ),
        (
            "Navigation, GNSS & positioning",
            r"gps|gnss|rtk|waypoint|position|location|satellite|home_lat|"
            r"home_lon|beacon|uwb",
        ),
        (
            "Gimbal & camera",
            r"gimbal|camera|zenmuse|pano|pitch_to_center|rot_camera",
        ),
        (
            "Motors, ESC & propulsion",
            r"motor|esc|engine|propeller|prop_|actuator|mixer|idle_level|"
            r"thrust|ppm_|arm_stop",
        ),
        (
            "IMU, compass & calibration controls",
            r"imu|compass|magnet|baro|gyro|gyr_|acc_|sensor|calib|cali_|"
            r"bias|temperature|press_alti",
        ),
        (
            "Flight modes & handling",
            r"mode_|control_mode|rc_scale|exp_mid|vert_vel|vert_acc|"
            r"horiz_(cur|max|vel|acc)|tilt_atti|tors_gyro|brake|stick|"
            r"fswitch|atti_range|yaw_rate|lift_exp|manual_actual|fpv_",
        ),
        (
            "Stabilization, gains & filters",
            r"notch|lpf|lowpass|filter|fltr|gain|pid|auto_tun|sweep|"
            r"sdft|ffwd|fdbk|comp_fc|boost_freq|boost_gain|adapt_",
        ),
        (
            "RC, radio, lights & interfaces",
            r"(^|[._|])rc|sbus|sdr|radio|wifi|antenna|led|lamp|usb|uart|"
            r"cloudctrl|app_enable",
        ),
        (
            "Diagnostics, service & simulator",
            r"debug|sim_|simulator|(^|[._|])test|factory|status|statistical|"
            r"fault|history|busy|counter|packet_cnt|hms|monitor|reboot|"
            r"user_info|sweep_",
        ),
    ]
    for category, pattern in rules:
        if re.search(pattern, text):
            return category
    return "Other / opaque internal"


RUNTIME_PATTERN = re.compile(
    r"status|statistical|fault_sum|history|busy|packet_cnt|counter|"
    r"device_gyr|acc_ground|vel_ground|press_alti|temperature|\.q\[|"
    r"\.m[xyz]$|\.acc_[xyz]$|\.gyro_[xyz]$|battery_status|total_",
    re.IGNORECASE,
)


def likely_runtime(row: dict) -> bool:
    return close(row["min"], row["max"]) or bool(
        RUNTIME_PATTERN.search(row["raw_name"])
    )


def build_catalogs():
    source_rows = {model: read_export(filename) for model, filename in PROFILES.items()}

    union_find = UnionFind()
    for rows in source_rows.values():
        for row in rows:
            aliases = row["raw_name"].split("|")
            for alias in aliases:
                union_find.find(alias)
            for alias in aliases[1:]:
                union_find.union(aliases[0], alias)

    components: dict[str, set[str]] = defaultdict(set)
    for alias in union_find.parent:
        components[union_find.find(alias)].add(alias)

    primary_for_alias = {}
    aliases_for_primary = {}
    for aliases in components.values():
        primary = choose_primary(aliases)
        aliases_for_primary[primary] = aliases
        for alias in aliases:
            primary_for_alias[alias] = primary

    catalogs: dict[str, dict[str, dict]] = {}
    for model, rows in source_rows.items():
        catalog = {}
        for row in rows:
            first_alias = row["raw_name"].split("|")[0]
            primary = primary_for_alias[first_alias]
            if primary in catalog:
                raise RuntimeError(f"Alias collapse produced a duplicate for {model}: {primary}")
            enriched = dict(row)
            enriched["name"] = primary
            enriched["aliases"] = aliases_for_primary[primary]
            enriched["category"] = category_for(primary, enriched["aliases"])
            (
                enriched["calibration_scope"],
                enriched["transferability"],
            ) = calibration_scope_for(primary, enriched["aliases"])
            enriched["likely_runtime"] = likely_runtime(enriched)
            catalog[primary] = enriched
        catalogs[model] = catalog
    return catalogs, aliases_for_primary


def duplicate_pair_checks():
    checks = {}
    for model, (v1_file, v2_file) in DUPLICATE_PAIRS.items():
        left = {row["raw_name"]: row for row in read_export(v1_file)}
        right = {row["raw_name"]: row for row in read_export(v2_file)}
        value_differences = [
            name
            for name in sorted(left.keys() & right.keys())
            if not close(left[name]["value"], right[name]["value"])
        ]
        checks[model] = {
            "v1_file": v1_file,
            "v2_file": v2_file,
            "same_name_set": set(left) == set(right),
            "name_count": len(left),
            "same_min_max_default": all(
                close(left[name][field], right[name][field])
                for name in left.keys() & right.keys()
                for field in ("min", "max", "default")
            ),
            "different_current_value_count": len(value_differences),
            "different_current_values": value_differences,
        }
    return checks


def write_outputs(catalogs: dict[str, dict[str, dict]], aliases_for_primary):
    models = list(PROFILES)
    union = set().union(*(set(catalog) for catalog in catalogs.values()))
    intersection = set.intersection(*(set(catalog) for catalog in catalogs.values()))
    presence = Counter(name for catalog in catalogs.values() for name in catalog)

    unique_by_model = {
        model: sorted(name for name in catalog if presence[name] == 1)
        for model, catalog in catalogs.items()
    }

    identical_common_definitions = [
        name
        for name in intersection
        if all(
            close(catalogs[models[0]][name][field], catalogs[model][name][field])
            for model in models[1:]
            for field in ("min", "max", "default")
        )
    ]
    identical_common_values = [
        name
        for name in intersection
        if all(
            close(catalogs[models[0]][name]["value"], catalogs[model][name]["value"])
            for model in models[1:]
        )
    ]

    summary = {
        "profiles": {
            model: {
                "file": PROFILES[model],
                "parameter_count": len(catalogs[model]),
                "unique_parameter_count": len(unique_by_model[model]),
                "current_value_differs_from_default": sum(
                    not close(row["value"], row["default"])
                    for row in catalogs[model].values()
                ),
                "non_runtime_value_differs_from_default": sum(
                    not row["likely_runtime"]
                    and not close(row["value"], row["default"])
                    for row in catalogs[model].values()
                ),
            }
            for model in models
        },
        "union_parameter_count": len(union),
        "intersection_parameter_count": len(intersection),
        "common_identical_min_max_default_count": len(identical_common_definitions),
        "common_identical_current_value_count": len(identical_common_values),
        "per_aircraft_calibration_state_union_count": sum(
            any(
                name in catalog
                and catalog[name]["calibration_scope"]
                == "per_aircraft_calibration_or_bias_state"
                for catalog in catalogs.values()
            )
            for name in union
        ),
        "presence_histogram": dict(sorted(Counter(presence.values()).items())),
        "duplicate_export_checks": duplicate_pair_checks(),
    }
    (ROOT / "analysis_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    matrix_fields = [
        "semantic_name",
        "aliases",
        "category",
        "calibration_scope",
        "transferability",
        "present_in_profiles",
    ]
    for model in models:
        matrix_fields.extend(
            [
                f"{model} | value",
                f"{model} | default",
                f"{model} | min",
                f"{model} | max",
            ]
        )
    with (ROOT / "parameter_matrix.csv").open("w", newline="", encoding="utf-8-sig") as fh:
        writer = csv.DictWriter(fh, fieldnames=matrix_fields)
        writer.writeheader()
        for name in sorted(union):
            aliases = aliases_for_primary[name]
            sample = next(catalog[name] for catalog in catalogs.values() if name in catalog)
            out = {
                "semantic_name": name,
                "aliases": " | ".join(sorted(aliases)),
                "category": sample["category"],
                "calibration_scope": sample["calibration_scope"],
                "transferability": sample["transferability"],
                "present_in_profiles": presence[name],
            }
            for model in models:
                row = catalogs[model].get(name)
                if row:
                    for field in ("value", "default", "min", "max"):
                        out[f"{model} | {field}"] = display(row[field])
            writer.writerow(out)

    with (ROOT / "unique_parameters.csv").open("w", newline="", encoding="utf-8-sig") as fh:
        fields = ["model", "category", "semantic_name", "aliases", "default", "value", "min", "max"]
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        for model in models:
            for name in unique_by_model[model]:
                row = catalogs[model][name]
                writer.writerow(
                    {
                        "model": model,
                        "category": row["category"],
                        "semantic_name": name,
                        "aliases": " | ".join(sorted(row["aliases"])),
                        "default": display(row["default"]),
                        "value": display(row["value"]),
                        "min": display(row["min"]),
                        "max": display(row["max"]),
                    }
                )

    with (ROOT / "category_summary.csv").open("w", newline="", encoding="utf-8-sig") as fh:
        fields = ["model", "category", "parameter_count", "unique_parameter_count"]
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        for model in models:
            counts = Counter(row["category"] for row in catalogs[model].values())
            unique_counts = Counter(
                catalogs[model][name]["category"] for name in unique_by_model[model]
            )
            for category in sorted(counts):
                writer.writerow(
                    {
                        "model": model,
                        "category": category,
                        "parameter_count": counts[category],
                        "unique_parameter_count": unique_counts[category],
                    }
                )

    with (ROOT / "pairwise_comparison.csv").open("w", newline="", encoding="utf-8-sig") as fh:
        fields = [
            "model_a",
            "model_b",
            "shared_parameters",
            "union_parameters",
            "jaccard_percent",
            "same_default_among_shared",
            "same_definition_among_shared",
            "only_in_a",
            "only_in_b",
        ]
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        for index, model_a in enumerate(models):
            for model_b in models[index + 1 :]:
                a, b = catalogs[model_a], catalogs[model_b]
                shared = set(a) & set(b)
                joined = set(a) | set(b)
                writer.writerow(
                    {
                        "model_a": model_a,
                        "model_b": model_b,
                        "shared_parameters": len(shared),
                        "union_parameters": len(joined),
                        "jaccard_percent": round(100 * len(shared) / len(joined), 2),
                        "same_default_among_shared": sum(
                            close(a[name]["default"], b[name]["default"])
                            for name in shared
                        ),
                        "same_definition_among_shared": sum(
                            all(close(a[name][field], b[name][field]) for field in ("min", "max", "default"))
                            for name in shared
                        ),
                        "only_in_a": len(set(a) - set(b)),
                        "only_in_b": len(set(b) - set(a)),
                    }
                )

    with (ROOT / "changed_from_default.csv").open("w", newline="", encoding="utf-8-sig") as fh:
        fields = [
            "model",
            "category",
            "semantic_name",
            "raw_name",
            "default",
            "value",
            "min",
            "max",
            "interpretation",
        ]
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        for model in models:
            for name, row in sorted(catalogs[model].items()):
                if close(row["default"], row["value"]):
                    continue
                writer.writerow(
                    {
                        "model": model,
                        "category": row["category"],
                        "semantic_name": name,
                        "raw_name": row["raw_name"],
                        "default": display(row["default"]),
                        "value": display(row["value"]),
                        "min": display(row["min"]),
                        "max": display(row["max"]),
                        "interpretation": "runtime_or_read_only" if row["likely_runtime"] else "configuration_or_persistent_state",
                    }
                )

    with (ROOT / "per_drone_calibration.csv").open("w", newline="", encoding="utf-8-sig") as fh:
        fields = [
            "model",
            "scope",
            "semantic_name",
            "raw_name",
            "value",
            "default",
            "min",
            "max",
            "transferability",
        ]
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        for model in models:
            for name, row in sorted(catalogs[model].items()):
                if not row["calibration_scope"]:
                    continue
                writer.writerow(
                    {
                        "model": model,
                        "scope": row["calibration_scope"],
                        "semantic_name": name,
                        "raw_name": row["raw_name"],
                        "value": display(row["value"]),
                        "default": display(row["default"]),
                        "min": display(row["min"]),
                        "max": display(row["max"]),
                        "transferability": row["transferability"],
                    }
                )


def main() -> None:
    catalogs, aliases_for_primary = build_catalogs()
    write_outputs(catalogs, aliases_for_primary)
    print("Wrote analysis_summary.json and six CSV appendices.")


if __name__ == "__main__":
    main()
