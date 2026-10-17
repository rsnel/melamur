# Draft: request for API / test access

Send to Malta Public Transport (operator of Tallinja) and/or Meep, who run the
backend. Public contacts: `customercare@publictransport.com.mt`,
`info@publictransport.com.mt`; Meep (the platform) via `meep.me`.

---

**Subject:** Request for test API access — MelaMur, a companion bus app for Malta

Dear Malta Public Transport team,

I am developing a small, non-commercial companion Android app called **MelaMur**
that helps bus passengers in Malta see their route on a map, know their next
stop, and get a reminder of when to press the stop button. It is a personal,
open-source project (https://github.com/rsnel/melamur) and is not intended to
replace or compete with the official Tallinja app.

To show live routes, stops and arrival times accurately, I would like to use the
same data backend the official app uses (the Meep / OpenTripPlanner API at
api.meep.me, Malta zone). I would be grateful for:

1. **A test API key** (application key for the anonymous SDK token endpoint),
   for development and a small number of test devices only.
2. Confirmation of any **terms, rate limits or attribution** you require, which
   I will honour. I expect very low request volumes during testing.
3. If direct API access is not something you grant to third parties, whether I
   may instead rely on your **GTFS / GTFS-Realtime** feeds, and where to obtain
   the realtime feed.

To keep load on your servers minimal, my app also contributes anonymous,
consent-based GPS traces from riders (no personal identifiers) that could help
improve realtime accuracy; I am happy to share this data back with you if useful.

I am of course willing to sign any data-use or developer agreement you require,
and to add clear attribution to Malta Public Transport in the app.

Thank you for considering this. I would be glad to answer any questions.

Kind regards,
Rik Snel
rik@snel.it
https://github.com/rsnel/melamur

---

*Note:* until a key is granted, MelaMur runs on open data only — OpenStreetMap
for the map and bus stops, and the public Malta GTFS feed for route shapes,
colours and timetables. It does not call the Tallinja backend without
authorisation.
