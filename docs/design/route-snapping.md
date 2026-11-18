# A GPS route along the roads

A phone in a city gets its position from satellite signals that bounce off buildings. The error drifts slowly, often
10 to 20 m for a minute at a time, so a walk drawn from the fixes wanders across the street, cuts corners through blocks
and lands on buildings. The Kalman and RTS smoothing in `Route.smooth` takes out the jitter, but it can't take out a
bias that lasts a minute: nothing in the fixes says where the street is.

The map does. The route card can now draw the walk along the roads it most likely took ("Roads"), or as GPS recorded
it ("GPS"). Distance, pace, splits and bests stay the smoothed track's: this changes the line on the map, not what is
measured.

## What was compared

| Option | Free? | Where the route goes | Notes |
|---|---|---|---|
| Google Roads API (snap to roads) | No, paid per request, API key | Google | Made for vehicles; 100 points per request |
| Mapbox Map Matching | Free tier, then paid; API key | Mapbox | Walking profile; 100 points per request |
| Stadia Maps (Valhalla) | Free for non-commercial use only; API key | Stadia | Credit-based |
| GraphHopper Directions API | 500 credits a day, non-commercial only; API key | GraphHopper | |
| OSRM public demo server | Free, "reasonable, non-commercial", at most 1 request a second | FOSSGIS servers | A demo, not for apps |
| GraphHopper or Valhalla on the phone | Free, open source | Nowhere | Needs a routing graph per region (hundreds of MB), a native or large library |
| Overpass API for the roads | Free, fair use (about 10,000 requests a day for all) | Roughly where you walked | Shared community servers |
| **The map's own vector tiles, matched on the phone** | **Free, no key** | **Nowhere new** | The same OpenFreeMap tiles the map already loads |

Every hosted service sends the whole route to a company, needs an API key and is either paid or non-commercial only.
That conflicts with unstrap's "no account, no key, your route never leaves the phone". The routing engines that run on a
phone need a separate download of the road network per region.

OpenFreeMap's z14 tiles already carry every road, footway, path and cycleway in OpenMapTiles' `transportation` layer
(`class`, `brunnel`, `layer`), at about 0.6 m per tile unit. They're free with no limits on map views, and OpenFreeMap's
terms allow apps to fetch them with a user agent and at most 4 requests at a time. So unstrap reads that layer from the
same tiles and matches the route itself.

## How it works (`app/.../maps/`)

1. **Tiles** (`RoadTiles`): the z14 tiles covering the route plus 100 m, at most 36 (about 14 km square), 4 at a time,
   the URL taken from OpenFreeMap's TileJSON. Kept in the cache directory for 30 days, at most 40 MB.
2. **Decoding** (`VectorTile`): a small reader for the vector tile protobuf. It takes only line features and the three
   tags it needs. No new library.
3. **Graph** (`RoadGraph`): road pieces within 60 m of the track, noded. A z14 tile's lines often don't share a vertex
   where they meet. In a Bengaluru city-centre tile, 1,641 crossings and 1,331 line ends touched another road with no
   shared point. So pieces on the same level are split where they cross, ends within 1.5 m join the road they stop at,
   and points within 1 m are one node. Bridges and tunnels don't meet the roads they pass over or under.
4. **Matching** (`RoadMatcher`): hidden Markov map matching (Newson and Krumm, 2009), as GraphHopper, OSRM and Valhalla
   use. The smoothed track is sampled every 20 m. A sample can be on any of the 8 nearest roads within reach, or "off
   the map" for a square, a park or a path the map doesn't have. Emission is Gaussian in the distance, with sigma the
   fix's accuracy but at least 7 m, since pavements lie beside the centre line. Transition is
   exp(-|route - straight| / 4 m), with the route distance from a bounded Dijkstra. Two extras matter for walkers:
   - a move back along a road while the walk goes on pays double;
   - out-and-back tails around a sample are taken out.
5. **Showing it** (`RouteSnapper`, `RouteMap`): under half the samples on roads, or a route too large, and it stays the
   GPS line with a note under the map. The matched line keeps the track's times, so it colours by heart rate zone as
   before. The choice is a setting (`route_roads`, backed up), and with GPS chosen nothing is fetched.

The map itself also changed a little: streets are a few shades lighter, since OpenFreeMap's dark style draws them at
#181818 on #0C0C0C, and the route line has a dark edge so its colour reads over anything.

## How it was tuned

A Python prototype took the roads of a real OpenFreeMap tile (central Bengaluru). It made walks along them on the
pavement 4 m from the centre line, and phone fixes with drifting error. The error was an AR(1) bias with a time constant
of about 30 s, 1.5 m white noise, occasional 15 to 35 m multipath jumps, and Doppler speed and bearing. It then compared
the lines. "Off the street" is the share of the drawn line more than 8 m from the street walked:

| GPS error (per axis) | Smoothed track: off the street | Snapped: off the street | Snapped length error |
|---|---|---|---|
| 6 m | 26 % | 6 % | 2 % |
| 10 m | 40 % | 9 % | 3 % |
| 15 m | 51 % | 13 % | 4 % |

The Kotlin code gives the same results on the same 20 walks: 39.6 % for the track against 7.9 % snapped at 10 m.

The settings came from sweeps:
- Samples every 20 m beat 5 to 12 m: denser samples follow the drift.
- beta 3 to 5 m.
- Snapping the smoothed track beat snapping the raw fixes: at 15 m, 13 % against 24 % off the street.

Where a stretch of path is missing from the map, the snapped line stays about as close as the track (7.8 m against
6.9 m mean distance), through the "off the map" state.

What's left: two roads under about 15 m apart (a dual carriageway, a service road beside a main road) can swap when the
error is that large. A matching phone run is about 30 to 40 ms on the JVM for 1.5 km, after the tiles are in.

## Not done here

- The live map still draws the GPS line. Matching once a minute while recording would work, but the route is still
  being written.
- The share card draws the route without a map and still uses the raw fixes.
- Distance stays the track's. The snapped length was within 2 to 4 % of the walk in the simulations, about as close as
  the track. Using it would change splits, bests and VO2max, and only helps when the match is right.
