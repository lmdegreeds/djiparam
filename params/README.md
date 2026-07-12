# Параметры — дампы и сравнительный анализ

Экспорты таблиц параметров полётного контроллера с нескольких DUML-совместимых бортов
и воспроизводимый сравнительный анализ. Дампы содержат только определения и значения
параметров (имя, индекс, тип, min/max/default/value) — без серийников и прочих
идентификаторов устройства.

## Дампы

| файл | модель | формат |
|---|---|---|
| `dji air3 dh v1.dhp`, `dji air3 dh v2.dhv2params` | DJI Air 3 | `.dhp` / `.dhv2params` |
| `dji air 3s dhv1.dhp`, `dji air3s.dhv2params` | DJI Air 3S | `.dhp` / `.dhv2params` |
| `Mini 4 Pro.dhp` | DJI Mini 4 Pro | `.dhp` |
| `Neo.dhp`, `Neo 2.dhp` | DJI Neo / Neo 2 | `.dhp` |
| `Flip.dhp` | DJI Flip | `.dhp` |
| `Avata 360.dhp` | DJI Avata 360 | `.dhp` |
| `dh-params-litox1-03.07-clean.dhv2params` | Lito X1 (`wa151`) | `.dhv2params` |

Форматы описаны в корневом [README](../README.md#форматы-файлов-параметров).

## Анализ

- **[`DJI_PARAMS_REPORT_RU.md`](DJI_PARAMS_REPORT_RU.md)** — сводный отчёт по всем профилям.
- `analyze_params.py` — скрипт нормализации и сравнения (генерирует CSV/JSON ниже).
- `parameter_matrix.csv` — матрица «параметр × модель» (value/default/min/max).
- `changed_from_default.csv` — параметры, отличающиеся от значения по умолчанию.
- `per_drone_calibration.csv` — параметры калибровочного скоупа и их переносимость.
- `category_summary.csv`, `unique_parameters.csv`, `pairwise_comparison.csv`,
  `analysis_summary.json` — сводки и попарные сравнения.

Пересобрать анализ:

```bash
python analyze_params.py
```

> ⚠️ Значения из дампов **нельзя** переносить между моделями/прошивками вслепую: параметр
> привязан к прошивке по индексу, а часть значений — device-specific калибровка. Сверяйте
> имя через `get_info` (см. корневой README).
