# Таблицы параметров по моделям (bundled param tables)

Сведённый набор из ТРЁХ приложений (`different_apps/`):
`app.parameditv5v6.apk` (`app.paramedit`) и `app.ledgpsforcefly.apk` (`app.ledgps`) —
9 моделей DJI (ассеты в обоих APK **побайтово идентичны**); плюс `fcc.apk`
(`com.djifcc.dronecontrol`) — добавил **WA151_LitoX1** (целевой борт), которой в
редакторах нет. Остальные 5 таблиц fcc построчно совпадают с редакторскими.

## Что здесь

- `paramtable_<codename>.txt` — **11** таблиц параметров FLYC (9 DJI + LitoX1 + Neo 2/WA020).
- `codenames.tsv` / `codenames.json` — индекс: кодовое имя → модель → CRC → файл.
- `known_toggles.tsv` — сводные известные тумблеры (ATTI/LED/GPS/maxspeed/CE) из
  `ledgps.Feat` + дефолтов fcc + CLAUDE.md: имя параметра, on/off-значения, модели.
- Разбор транспорта/записи/«FCC-хака» fcc — в `ANALISYS_2/fcc-app-djifcc-analysis.md`.

### Neo 2 (WA020) — две ревизии прошивки

`paramtable_wa020.txt` снят с **последней** прошивки: `crc=2ae1a5ad count=1571
params=934`. Существует и более старая ревизия (dump `params/Neo 2.dhp`,
count 1544, 927 строк), её CRC пока **неизвестен**. Между ревизиями DJI вставила
блок Remote-ID/FDI-параметров в середину таблицы, поэтому **индексы съехали**:
`fswitch_selection` 466→130, `ce_country_type` 443→47, `gps_enable` 53→377,
`g_config.flying_limit.max_height` 408→74. На месте остались только LED в начале
таблицы (`ext_led_ctrl` idx 3, `forearm_led_ctrl` idx 4). 7 реально новых имён:
`support_china_oid`, `support_enforce_realname_prevent`, `oid_link_disconnected`,
`ccc_broadcast_signal_quality`, `ccc_poor_position_accuracy_on`,
`ccc_unsupport_control_type`, `fscap_enable_homepoint_setting_s`. **Вывод:**
резолвить тумблеры по имени через `get_info`, не по захардкоженному индексу.

### LitoX1 vs Mini 5 Pro
`wa150` и `wa151` делят CRC `5f8b2ae1` → **по CRC их не различить**; разделять по
`count` (1557 vs 1593) или пробой ~12 отличающихся строк. У LitoX1 `fswitch_selection`
(ATTI) = idx **146**, у Mini 5 Pro = **138** (то же имя, разный индекс).

## Формат файла `paramtable_*.txt`

Первая строка — заголовок-комментарий:

```
#crc=<hex32> model=<CODENAME_Friendly> count=<N> params=<M>
```

- `crc` — CRC всей таблицы параметров прошивки (u32, hex). `0` = у автора не было
  забито значение для этой модели (тогда работает только probe по именам).
- `model` — бандл кодового имени и человекочитаемого: `WM260_Mavic3`.
- `count` — общее число слотов в таблице прошивки (из ответа `0xE0`).
- `params` — число строк с именами в этом файле.

Остальные строки — TSV, 6 полей через `\t`:

```
index <TAB> type <TAB> default <TAB> min <TAB> max <TAB> name
```

- `index` — индекс параметра в таблице 0 (u16).
- `type` — числовой код типа. **6 и 8 = float (F32)**; остальные — целое,
  знаковость/размер выводятся из `get_info` с борта (min<0 ⇒ signed).
- `default / min / max` — строкой (float или int).
- `name` — может быть двойным: `short|g_config.full.path` (разделитель `|`).

## Как эти приложения определяют модель (алгоритм)

Кодовое имя **нигде не является ключом поиска** — только меткой. Идентификация:

1. `Fc.tableInfo()` → DUML `cmd_set=0x03, cmd_id=0xE0`. Возвращает `count` и
   **CRC таблицы прошивки**.
2. **Матч по CRC:** если CRC борта == `crc` из заголовка какого-то файла — берётся
   он напрямую (мгновенно, точно).
3. **Fallback `probe()`:** если CRC=0 или не совпал — читаются реальные имена
   параметров с борта через `Fc.nameAt()` (`0xE1 get_info`) на ~5 сэмпл-индексах и
   сравниваются с именами в каждой таблице. Побеждает таблица с максимумом
   совпадений (порог **≥2**).
4. `Fc.identity()` (`cmd_set=0x00, cmd_id=0x01`) — только для показа строки в UI,
   на выбор таблицы **не влияет**.

`app.ledgps` дополнительно кэширует `модель` по ключу `crc_<hex>` в SharedPreferences
и гейтит фичи по модели через `startsWith(model, "WA234")` (поле `onlyModels`).

## Как переиспользовать для новой модели (напр. Lito X1 / wa151)

Снять таблицу с борта в этот же TSV-формат и положить рядом файлом
`paramtable_wa151.txt` с заголовком `#crc=<crc с 0xE0> model=WA151_LitoX1 count=.. params=..`.
Дальше та же логика CRC-матч → probe заработает без изменений.
