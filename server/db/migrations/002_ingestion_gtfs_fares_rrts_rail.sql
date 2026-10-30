-- Phase 4: GTFS Feeds, Transit Edges, First/Last Trains, Operator Fare Matrices/Slabs & Static Rail Schedules
CREATE TABLE IF NOT EXISTS transit_feeds (
    feed_id TEXT PRIMARY KEY,
    operator_code TEXT NOT NULL,
    source_url TEXT NOT NULL,
    sha256_hash TEXT NOT NULL,
    validator_passed BOOLEAN NOT NULL,
    effective_from DATE NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS transit_routes (
    route_id TEXT PRIMARY KEY,
    operator_code TEXT NOT NULL,
    mode TEXT NOT NULL CHECK (mode IN ('metro', 'rrts', 'rail', 'bus')),
    line_name TEXT NOT NULL,
    line_color TEXT,
    source_url TEXT NOT NULL,
    effective_from DATE NOT NULL
);

CREATE TABLE IF NOT EXISTS transit_stop_edges (
    id BIGSERIAL PRIMARY KEY,
    route_id TEXT NOT NULL REFERENCES transit_routes(route_id) ON DELETE CASCADE,
    from_station_id TEXT NOT NULL REFERENCES canonical_stations(id),
    to_station_id TEXT NOT NULL REFERENCES canonical_stations(id),
    sequence_no INTEGER NOT NULL,
    distance_km NUMERIC(6, 2) NOT NULL,
    scheduled_travel_s INTEGER NOT NULL,
    headway_peak_s INTEGER NOT NULL,
    headway_offpeak_s INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS first_last_trains (
    route_id TEXT NOT NULL REFERENCES transit_routes(route_id) ON DELETE CASCADE,
    station_id TEXT NOT NULL REFERENCES canonical_stations(id),
    direction_station_id TEXT NOT NULL REFERENCES canonical_stations(id),
    day_type TEXT NOT NULL CHECK (day_type IN ('weekday', 'saturday', 'sunday', 'holiday')),
    first_train_time TEXT NOT NULL,
    last_train_time TEXT NOT NULL,
    source_url TEXT NOT NULL,
    effective_from DATE NOT NULL,
    PRIMARY KEY (route_id, station_id, direction_station_id, day_type)
);

CREATE TABLE IF NOT EXISTS fare_od_matrix (
    operator_code TEXT NOT NULL,
    from_station_id TEXT NOT NULL REFERENCES canonical_stations(id),
    to_station_id TEXT NOT NULL REFERENCES canonical_stations(id),
    day_type TEXT NOT NULL DEFAULT 'weekday',
    fare_inr NUMERIC(8, 2) NOT NULL,
    operator_deeplink TEXT NOT NULL,
    source_url TEXT NOT NULL,
    effective_from DATE NOT NULL,
    PRIMARY KEY (operator_code, from_station_id, to_station_id, day_type)
);

CREATE TABLE IF NOT EXISTS fare_slabs (
    operator_code TEXT NOT NULL,
    day_type TEXT NOT NULL DEFAULT 'weekday',
    min_km NUMERIC(6, 2) NOT NULL,
    max_km NUMERIC(6, 2) NOT NULL,
    fare_inr NUMERIC(8, 2) NOT NULL,
    operator_deeplink TEXT NOT NULL,
    source_url TEXT NOT NULL,
    effective_from DATE NOT NULL,
    PRIMARY KEY (operator_code, day_type, min_km, max_km)
);

CREATE TABLE IF NOT EXISTS rail_static_schedules (
    train_number TEXT NOT NULL,
    train_name TEXT NOT NULL,
    from_station_id TEXT NOT NULL REFERENCES canonical_stations(id),
    to_station_id TEXT NOT NULL REFERENCES canonical_stations(id),
    departure_s INTEGER NOT NULL,
    arrival_s INTEGER NOT NULL,
    travel_duration_s INTEGER NOT NULL,
    running_days TEXT[] NOT NULL,
    operator_deeplink TEXT NOT NULL,
    source_url TEXT NOT NULL,
    effective_from DATE NOT NULL,
    PRIMARY KEY (train_number, from_station_id, to_station_id)
);
