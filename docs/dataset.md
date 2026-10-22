# MelaMur dataset

The app no longer bundles OpenStreetMap bus stops. On first start it asks the
user to pick a **Malta Public Transport dataset** — an `.sqlite` file that
contains routes, stops (with stop numbers), line numbers, line colours and
timetables. The full schema is documented in `docs/SQL-FORMAT.txt` (schema 1).

- Imported via the Storage Access Framework and copied into app-private storage
  (`DbManager`), opened read-only. Validated by `meta.schema_version == "1"`.
- Reload or replace it from the burger menu (Reload database).
- `meta.attribution` is shown in About and must be displayed (Malta Public
  Transport + OpenStreetMap credits).
- Still bundled: `map.bin` (vector base map) and `labels.json` (place and street
  names), used for the map and for address/landmark search.

Stops, the lines serving a stop, route geometry/colours, departures and the
journey planner all read from this database (`MptDb`), resolving one dataset
version per service date as the format requires.
