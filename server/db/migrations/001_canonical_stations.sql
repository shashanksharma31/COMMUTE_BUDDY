-- Phase 3: Canonical Stations & Interchange Hub Walking Links (PostgreSQL + PostGIS + pg_trgm)
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS canonical_stations (
    id TEXT PRIMARY KEY,
    canonical_name TEXT NOT NULL,
    names_aliases TEXT[] NOT NULL DEFAULT '{}',
    city_code TEXT NOT NULL,
    station_type TEXT NOT NULL CHECK (station_type IN ('metro', 'rail', 'rrts', 'bus')),
    location GEOGRAPHY(Point, 4326) NOT NULL,
    h3_res10 TEXT NOT NULL,
    gtfs_stop_ids TEXT[] NOT NULL DEFAULT '{}',
    ir_station_code TEXT,
    osm_node_ids BIGINT[] NOT NULL DEFAULT '{}',
    google_place_id TEXT,
    wikidata_id TEXT,
    confirming_sources TEXT[] NOT NULL DEFAULT '{}',
    source_url TEXT NOT NULL,
    effective_from DATE NOT NULL,
    is_verified BOOLEAN GENERATED ALWAYS AS ( COALESCE(array_length(confirming_sources, 1), 0) >= 2 ) STORED,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_canonical_stations_location ON canonical_stations USING GIST (location);
CREATE INDEX IF NOT EXISTS idx_canonical_stations_h3 ON canonical_stations (h3_res10);
CREATE INDEX IF NOT EXISTS idx_canonical_stations_trgm ON canonical_stations USING GIN (canonical_name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_canonical_stations_ir_code ON canonical_stations (ir_station_code);

CREATE TABLE IF NOT EXISTS station_hub_links (
    hub_id TEXT NOT NULL,
    from_station_id TEXT NOT NULL REFERENCES canonical_stations(id) ON DELETE CASCADE,
    to_station_id TEXT NOT NULL REFERENCES canonical_stations(id) ON DELETE CASCADE,
    walk_distance_m INTEGER NOT NULL CHECK (walk_distance_m > 0 AND walk_distance_m <= 400),
    walk_duration_s INTEGER NOT NULL CHECK (walk_duration_s > 0),
    source_url TEXT NOT NULL,
    effective_from DATE NOT NULL,
    PRIMARY KEY (from_station_id, to_station_id)
);
