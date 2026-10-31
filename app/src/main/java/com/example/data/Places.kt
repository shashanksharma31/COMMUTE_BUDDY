package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class DelhiPlace(
    val id: String,
    val name: String,
    val area: String,
    val category: String,
    val lat: Double,
    val lng: Double,
    val stationId: String? = null,
    val aliases: List<String> = emptyList()
)

// Multi-API Pan-India place search and geocoding client combining:
// 1. ArcGIS World Geocoding Service REST API (street-level Indian POI, address, bus stand, temple, college lookup)
// 2. Komoot Photon Geocoder API (OpenStreetMap POI search)
// 3. OpenStreetMap Nominatim Search & Reverse Geocoding API
// 4. Open-Meteo Global Geocoding API (city, town, district, locality lookup)
// 5. Curated Pan-India verified transit hubs with distinct per-place coordinates (no shared-coordinate bundling)
object DelhiPlacesData {
    private var currentBackendUrl: String = "http://10.0.2.2:3001"
    private var backendReachable: Boolean? = null

    var backendBaseUrl: String
        get() = currentBackendUrl
        set(value) {
            currentBackendUrl = value
            backendReachable = null
        }

    private const val ARCGIS_FIND_URL =
        "https://geocode.arcgis.com/arcgis/rest/services/World/GeocodeServer/findAddressCandidates"
    private const val PHOTON_BASE_URL = "https://photon.komoot.io/api/"
    private const val NOMINATIM_SEARCH_URL = "https://nominatim.openstreetmap.org/search"
    private const val NOMINATIM_REVERSE_URL = "https://nominatim.openstreetmap.org/reverse"
    private const val OPEN_METEO_GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
    private const val OSM_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; CommuteBuddy/3.0)"

    // Verified Pan-India transit hubs & landmarks — every entry is a SINGLE distinct real-world place
    // with its own exact GPS coordinates so two different places never share identical coordinates.
    val places: List<DelhiPlace> = listOf(
        // --- Mathura, Vrindavan & Braj ---
        DelhiPlace(
            id = "stn_in_mtj_rail",
            name = "Mathura Junction Railway Station",
            area = "Civil Lines, Mathura, Uttar Pradesh",
            category = "Railway Station",
            lat = 27.4795,
            lng = 77.6736,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Mathura Jn", "MTJ", "Mathura Junction", "Mathura Railway Station")
        ),
        DelhiPlace(
            id = "stn_in_mtj_bus",
            name = "Mathura New Bus Stand (Bhuteshwar ISBT)",
            area = "Goverdhan Chauraha / Bhuteshwar, Mathura, Uttar Pradesh",
            category = "Bus Stop",
            lat = 27.4938,
            lng = 77.6658,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Mathura New Bus Stand", "Mathura Bus Stand", "Mathura ISBT", "Bhuteshwar Bus Stand")
        ),
        DelhiPlace(
            id = "stn_in_mtj_old_bus",
            name = "Mathura Old Bus Stand (Mal Godam Road)",
            area = "Near Railway Station, Mathura, Uttar Pradesh",
            category = "Bus Stop",
            lat = 27.4844,
            lng = 77.6779,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Old Bus Stand Mathura", "Mal Godam Bus Stand")
        ),
        DelhiPlace(
            id = "plc_in_mtj_janmabhoomi",
            name = "Shri Krishna Janmasthan Temple, Mathura",
            area = "Janam Bhumi, Mathura, Uttar Pradesh",
            category = "Landmark",
            lat = 27.5048,
            lng = 77.6696,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Shri Krishna Janmabhoomi", "Krishna Janmabhoomi", "Janmabhoomi Mathura", "Janmasthan")
        ),
        DelhiPlace(
            id = "plc_in_mtj_dwarkadhish",
            name = "Dwarkadhish Temple, Mathura",
            area = "Pathak Gali, Vishram Bazar, Mathura, Uttar Pradesh",
            category = "Landmark",
            lat = 27.5055,
            lng = 77.6844,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Dwarkadhish Temple", "Dwarkadheesh Mathura")
        ),
        DelhiPlace(
            id = "plc_in_mtj_vishram_ghat",
            name = "Vishram Ghat, Mathura",
            area = "Yamuna River Front, Old Mathura, Uttar Pradesh",
            category = "Landmark",
            lat = 27.5034,
            lng = 77.6852,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Vishram Ghat")
        ),
        DelhiPlace(
            id = "plc_in_mtj_holigate",
            name = "Holi Gate, Mathura",
            area = "Holi Gate Market, Mathura, Uttar Pradesh",
            category = "Landmark",
            lat = 27.4956,
            lng = 77.6835,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Holigate", "Holi Gate Mathura")
        ),
        DelhiPlace(
            id = "plc_in_mtj_dampier",
            name = "Dampier Nagar, Mathura",
            area = "Near Government Museum, Mathura, Uttar Pradesh",
            category = "Locality",
            lat = 27.4912,
            lng = 77.6785,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Dampier Nagar", "Museum Road Mathura")
        ),
        DelhiPlace(
            id = "plc_in_mtj_gla",
            name = "GLA University, Mathura",
            area = "17 km Stone, NH-19, Chaumuhan, Mathura, Uttar Pradesh",
            category = "University",
            lat = 27.6057,
            lng = 77.5933,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("GLA University", "GLA Mathura")
        ),
        DelhiPlace(
            id = "plc_in_vrn_bankey_bihari",
            name = "Bankey Bihari Temple, Vrindavan",
            area = "Goda Vihar, Vrindavan, Mathura, Uttar Pradesh",
            category = "Landmark",
            lat = 27.5806,
            lng = 77.6905,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Bankey Bihari", "Banke Bihari Temple", "Vrindavan Temple")
        ),
        DelhiPlace(
            id = "plc_in_vrn_prem_mandir",
            name = "Prem Mandir, Vrindavan",
            area = "Raman Reti, Chhatikara Road, Vrindavan, Uttar Pradesh",
            category = "Landmark",
            lat = 27.5720,
            lng = 77.6718,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Prem Mandir", "Prem Mandir Vrindavan")
        ),
        DelhiPlace(
            id = "plc_in_vrn_iskcon",
            name = "ISKCON Temple (Krishna Balaram Mandir), Vrindavan",
            area = "Raman Reti, Vrindavan, Mathura, Uttar Pradesh",
            category = "Landmark",
            lat = 27.5731,
            lng = 77.6766,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("ISKCON Vrindavan", "Krishna Balaram Mandir")
        ),
        DelhiPlace(
            id = "plc_in_mtj_goverdhan",
            name = "Govardhan Shila & Parikrama Marg, Mathura",
            area = "Govardhan, Mathura District, Uttar Pradesh",
            category = "Landmark",
            lat = 27.4991,
            lng = 77.4648,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Govardhan", "Goverdhan", "Giriraj Ji")
        ),
        DelhiPlace(
            id = "plc_in_mtj_barsana",
            name = "Shri Radha Rani Temple, Barsana",
            area = "Barsana, Mathura District, Uttar Pradesh",
            category = "Landmark",
            lat = 27.6517,
            lng = 77.3770,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Barsana", "Radha Rani Temple Barsana")
        ),
        DelhiPlace(
            id = "plc_in_mtj_bhuteshwar_rail",
            name = "Bhuteshwar Railway Station, Mathura",
            area = "Bhuteshwar Tiraha, Mathura, Uttar Pradesh",
            category = "Railway Station",
            lat = 27.4962,
            lng = 77.6641,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Bhuteshwar", "Bhuteshwar Station", "Bhuteshwar Mahadev Temple")
        ),
        DelhiPlace(
            id = "plc_in_mtj_cantt_rail",
            name = "Mathura Cantt Railway Station (MRT)",
            area = "Civil Lines / Cantonment, Mathura, Uttar Pradesh",
            category = "Railway Station",
            lat = 27.4828,
            lng = 77.6869,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Mathura Cantt", "MRT Station", "Cantonment Mathura")
        ),
        DelhiPlace(
            id = "plc_in_mtj_dholi_pyau",
            name = "Dholi Pyau, Mathura",
            area = "Station Road, Dholi Pyau, Mathura, Uttar Pradesh",
            category = "Locality",
            lat = 27.4820,
            lng = 77.6742,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Dholi Pyau", "Dholi Pyau Mathura")
        ),
        DelhiPlace(
            id = "plc_in_mtj_masani",
            name = "Masani Tiraha, Mathura",
            area = "Masani Link Road, Mathura, Uttar Pradesh",
            category = "Locality",
            lat = 27.5112,
            lng = 77.6725,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Masani", "Masani Chauraha", "Masani Road Mathura")
        ),
        DelhiPlace(
            id = "plc_in_mtj_krishna_nagar",
            name = "Krishna Nagar, Mathura",
            area = "Govardhan Road, Krishna Nagar, Mathura, Uttar Pradesh",
            category = "Locality",
            lat = 27.4968,
            lng = 77.6562,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Krishna Nagar Mathura", "Krishna Nagar")
        ),
        DelhiPlace(
            id = "plc_in_mtj_radhika_vihar",
            name = "Radhika Vihar, Mathura",
            area = "NH-19 / Krishna Nagar, Mathura, Uttar Pradesh",
            category = "Locality",
            lat = 27.4995,
            lng = 77.6488,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Radhika Vihar", "Radhika Vihar Phase 1", "Radhika Vihar Mathura")
        ),
        DelhiPlace(
            id = "plc_in_mtj_bsa_college",
            name = "BSA College, Mathura",
            area = "Gaushala Road, Civil Lines, Mathura, Uttar Pradesh",
            category = "University",
            lat = 27.4918,
            lng = 77.6712,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("BSA College", "Babu Shivnath Agrawal College", "BSA Engineering College Mathura")
        ),
        DelhiPlace(
            id = "plc_in_mtj_goverdhan_chauraha",
            name = "Govardhan Chauraha (NH-19), Mathura",
            area = "NH-19 Delhi-Agra Highway, Mathura, Uttar Pradesh",
            category = "Bus Stop",
            lat = 27.4952,
            lng = 77.6442,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Govardhan Chauraha", "Goverdhan Chauraha Mathura", "Mandi Chauraha Mathura")
        ),
        DelhiPlace(
            id = "plc_in_mtj_shri_om_hospital",
            name = "Shri Om Hospital, Mathura",
            area = "NH-19 / Bhuteshwar Road, Mathura, Uttar Pradesh",
            category = "Hospital",
            lat = 27.4926,
            lng = 77.6634,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Shri Om Hospital", "Om Hospital Mathura")
        ),
        DelhiPlace(
            id = "plc_in_mtj_saxena_hospital",
            name = "Saxena Hospital, Mathura",
            area = "Sonkh Road / Civil Lines, Mathura, Uttar Pradesh",
            category = "Hospital",
            lat = 27.4864,
            lng = 77.6665,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Saxena Hospital", "Saxena Hospital Mathura")
        ),
        DelhiPlace(
            id = "plc_in_mtj_sheetal_regency",
            name = "Sheetal Regency Hotel, Mathura",
            area = "Janmabhoomi Link Road, Mathura, Uttar Pradesh",
            category = "Landmark",
            lat = 27.5016,
            lng = 77.6682,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Sheetal Regency", "Sheetal Regency Hotel")
        ),
        DelhiPlace(
            id = "plc_in_mtj_refinery",
            name = "Mathura Refinery & Township (IOCL)",
            area = "NH-19, Baad, Mathura, Uttar Pradesh",
            category = "Business Hub",
            lat = 27.4125,
            lng = 77.6852,
            stationId = "stn_in_mtj_rail",
            aliases = listOf("Mathura Refinery", "Refinery Nagar Mathura", "Baad Mathura")
        ),


        // --- Ghaziabad, Siddharth Vihar, Indirapuram & Trans-Hindon ---
        DelhiPlace(
            id = "plc_in_gzb_prateek_grand_city",
            name = "Prateek Grand City, Siddharth Vihar",
            area = "Siddharth Vihar, NH-9, Ghaziabad, Uttar Pradesh",
            category = "Residential Township",
            lat = 28.6538,
            lng = 77.4092,
            stationId = "stn_in_gzb_rail",
            aliases = listOf("Prateek Grand City", "Prateek Grand Carnesia", "Prateek Grand Paeonia")
        ),
        DelhiPlace(
            id = "plc_in_gzb_siddharth_vihar",
            name = "Siddharth Vihar (Gaur Siddhartham)",
            area = "Siddharth Vihar, NH-9 Delhi-Meerut Expy, Ghaziabad",
            category = "Locality",
            lat = 28.6562,
            lng = 77.4068,
            stationId = "stn_in_gzb_rail",
            aliases = listOf("Siddharth Vihar", "Gaur Siddhartham", "Apex Quebec")
        ),
        DelhiPlace(
            id = "plc_in_gzb_indirapuram",
            name = "Indirapuram Habitat Centre",
            area = "Ahinsa Khand 1, Indirapuram, Ghaziabad",
            category = "Locality",
            lat = 28.6384,
            lng = 77.3714,
            stationId = "stn_in_del_anand_vihar_metro",
            aliases = listOf("Indirapuram", "Indirapuram Habitat Centre", "Shipra Mall Indirapuram")
        ),
        DelhiPlace(
            id = "plc_in_gzb_vaishali",
            name = "Vaishali Metro Station",
            area = "Sector 4, Vaishali, Ghaziabad, Uttar Pradesh",
            category = "Metro Station",
            lat = 28.6499,
            lng = 77.3397,
            stationId = "stn_in_del_anand_vihar_metro",
            aliases = listOf("Vaishali Metro", "Vaishali Ghaziabad")
        ),
        DelhiPlace(
            id = "plc_in_gzb_kaushambi",
            name = "Kaushambi Metro Station & ISBT",
            area = "Kaushambi, Ghaziabad, Uttar Pradesh",
            category = "Metro Station",
            lat = 28.6454,
            lng = 77.3243,
            stationId = "stn_in_del_anand_vihar_metro",
            aliases = listOf("Kaushambi", "Kaushambi Metro")
        ),
        DelhiPlace(
            id = "plc_in_gzb_vasundhara",
            name = "Vasundhara Sector 15, Ghaziabad",
            area = "Vasundhara Link Road, Ghaziabad, Uttar Pradesh",
            category = "Locality",
            lat = 28.6634,
            lng = 77.3765,
            stationId = "stn_in_del_sahibabad_rrts",
            aliases = listOf("Vasundhara", "Vasundhara Ghaziabad")
        ),
        DelhiPlace(
            id = "plc_in_gzb_crossings_republik",
            name = "Crossings Republik, Ghaziabad",
            area = "NH-9 / NH-24, Dundahera, Ghaziabad, Uttar Pradesh",
            category = "Residential Township",
            lat = 28.6290,
            lng = 77.4345,
            stationId = "stn_in_gzb_rail",
            aliases = listOf("Crossings Republik", "ABES Engineering College")
        ),
        DelhiPlace(
            id = "plc_in_gzb_raj_nagar_ext",
            name = "Raj Nagar Extension, Ghaziabad",
            area = "Meerut Bypass Road, Ghaziabad, Uttar Pradesh",
            category = "Locality",
            lat = 28.7031,
            lng = 77.4289,
            stationId = "stn_in_gzb_shaheed_sthal_metro",
            aliases = listOf("Raj Nagar Extension", "RDC Raj Nagar")
        ),
        DelhiPlace(
            id = "stn_in_gzb_rail",
            name = "Ghaziabad Junction Railway Station (GZB)",
            area = "Railway Colony, Ghaziabad, Uttar Pradesh",
            category = "Railway Station",
            lat = 28.6502,
            lng = 77.4316,
            stationId = "stn_in_gzb_rail",
            aliases = listOf("GZB", "Ghaziabad Junction", "Ghaziabad Railway Station")
        ),
        DelhiPlace(
            id = "stn_in_gzb_shaheed_sthal_metro",
            name = "Shaheed Sthal New Bus Adda Metro Station",
            area = "GT Road, Ghaziabad, Uttar Pradesh",
            category = "Metro Station",
            lat = 28.6705,
            lng = 77.4162,
            stationId = "stn_in_gzb_shaheed_sthal_metro",
            aliases = listOf("Shaheed Sthal Metro", "New Bus Adda Metro")
        ),

        // --- Noida & Greater Noida ---
        DelhiPlace(
            id = "plc_in_noi_sec_62",
            name = "Noida Electronic City Metro (Sector 62)",
            area = "Sector 62, NH-9 Corridor, Noida, Uttar Pradesh",
            category = "Metro Station",
            lat = 28.6280,
            lng = 77.3649,
            stationId = "stn_in_del_botanical_garden_metro",
            aliases = listOf("Sector 62 Noida", "Noida Electronic City", "Jaypee Institute Noida")
        ),
        DelhiPlace(
            id = "plc_in_del_noida_sec_18",
            name = "Noida Sector 18 Metro & DLF Mall of India",
            area = "Sector 18, Noida, Uttar Pradesh",
            category = "Metro Station",
            lat = 28.5708,
            lng = 77.3261,
            stationId = "stn_in_del_botanical_garden_metro",
            aliases = listOf("Atta Market", "DLF Mall of India", "Noida Sector 18")
        ),
        DelhiPlace(
            id = "stn_in_del_botanical_garden_metro",
            name = "Botanical Garden Metro Station (Noida Sec 38)",
            area = "Sector 38, Noida, Uttar Pradesh",
            category = "Metro Station",
            lat = 28.5641,
            lng = 77.3343,
            stationId = "stn_in_del_botanical_garden_metro",
            aliases = listOf("Botanical Garden Metro", "Noida Sector 38")
        ),
        DelhiPlace(
            id = "plc_in_noi_sec_51_76",
            name = "Noida Sector 51 / 52 Metro Interchange",
            area = "Sector 51 & 52, Central Noida, Uttar Pradesh",
            category = "Metro Station",
            lat = 28.5850,
            lng = 77.3752,
            stationId = "stn_in_del_botanical_garden_metro",
            aliases = listOf("Sector 51 Noida", "Sector 52 Noida", "Noida City Centre")
        ),
        DelhiPlace(
            id = "plc_in_grn_gaur_city",
            name = "Gaur City Chowk, Greater Noida West",
            area = "Gaur Chowk, Noida Extension, Uttar Pradesh",
            category = "Residential Township",
            lat = 28.6061,
            lng = 77.4294,
            stationId = "stn_in_del_botanical_garden_metro",
            aliases = listOf("Gaur City", "Noida Extension", "Greater Noida West", "Gaur Chowk")
        ),
        DelhiPlace(
            id = "plc_in_grn_pari_chowk",
            name = "Pari Chowk Metro Station, Greater Noida",
            area = "Knowledge Park, Greater Noida, Uttar Pradesh",
            category = "Metro Station",
            lat = 28.4671,
            lng = 77.5138,
            stationId = "stn_in_del_botanical_garden_metro",
            aliases = listOf("Pari Chowk", "Greater Noida", "Knowledge Park 2", "Sharda University")
        ),

        // --- Delhi NCR Major Hubs ---
        DelhiPlace(
            id = "stn_in_del_rajiv_chowk_metro",
            name = "Rajiv Chowk Metro Station (Connaught Place)",
            area = "Connaught Place, New Delhi",
            category = "Metro Station",
            lat = 28.6328,
            lng = 77.2197,
            stationId = "stn_in_del_rajiv_chowk_metro",
            aliases = listOf("Rajiv Chowk", "Connaught Place", "CP Delhi")
        ),
        DelhiPlace(
            id = "stn_in_del_new_delhi_metro",
            name = "New Delhi Metro Station",
            area = "Ajmeri Gate, New Delhi",
            category = "Metro Station",
            lat = 28.6425,
            lng = 77.2202,
            stationId = "stn_in_del_new_delhi_metro",
            aliases = listOf("New Delhi Metro", "Airport Express Metro")
        ),
        DelhiPlace(
            id = "stn_in_del_ndls_rail",
            name = "New Delhi Railway Station (NDLS)",
            area = "Paharganj / Ajmeri Gate, New Delhi",
            category = "Railway Station",
            lat = 28.6429,
            lng = 77.2191,
            stationId = "stn_in_del_ndls_rail",
            aliases = listOf("NDLS", "New Delhi Station", "New Delhi Railway Station")
        ),
        DelhiPlace(
            id = "stn_in_del_nzm_rail",
            name = "Hazrat Nizamuddin Railway Station (NZM)",
            area = "Sarai Kale Khan, South East Delhi",
            category = "Railway Station",
            lat = 28.5877,
            lng = 77.2518,
            stationId = "stn_in_del_nzm_rail",
            aliases = listOf("NZM", "Hazrat Nizamuddin", "Nizamuddin Station", "Sarai Kale Khan")
        ),
        DelhiPlace(
            id = "stn_in_del_kashmere_gate_metro",
            name = "Kashmere Gate ISBT & Metro Interchange",
            area = "Kashmere Gate, North Delhi",
            category = "Metro Station",
            lat = 28.6675,
            lng = 77.2282,
            stationId = "stn_in_del_kashmere_gate_metro",
            aliases = listOf("Kashmere Gate", "Kashmiri Gate", "ISBT Kashmere Gate")
        ),
        DelhiPlace(
            id = "plc_in_del_north_campus",
            name = "Vishwavidyalaya Metro (DU North Campus)",
            area = "GTB Nagar / Mall Road, North Delhi",
            category = "Metro Station",
            lat = 28.6936,
            lng = 77.2116,
            stationId = "stn_in_del_kashmere_gate_metro",
            aliases = listOf("North Campus", "Delhi University", "Vishwavidyalaya Metro")
        ),
        DelhiPlace(
            id = "plc_in_del_rohini_pitampura",
            name = "Netaji Subhash Place (NSP) & Pitampura",
            area = "Ring Road, Pitampura, North West Delhi",
            category = "Metro Station",
            lat = 28.6960,
            lng = 77.1526,
            stationId = "stn_in_del_kashmere_gate_metro",
            aliases = listOf("Netaji Subhash Place", "NSP Delhi", "Pitampura")
        ),
        DelhiPlace(
            id = "stn_in_del_hauz_khas_metro",
            name = "Hauz Khas Metro Station & IIT Delhi",
            area = "Outer Ring Road, Hauz Khas, South Delhi",
            category = "Metro Station",
            lat = 28.5433,
            lng = 77.2066,
            stationId = "stn_in_del_hauz_khas_metro",
            aliases = listOf("Hauz Khas", "IIT Delhi", "Hauz Khas Metro")
        ),
        DelhiPlace(
            id = "plc_in_del_saket_malviya",
            name = "Saket Select Citywalk & Malviya Nagar",
            area = "Press Enclave Marg, Saket, South Delhi",
            category = "Locality",
            lat = 28.5245,
            lng = 77.2066,
            stationId = "stn_in_del_hauz_khas_metro",
            aliases = listOf("Saket", "Saket Metro", "Select Citywalk")
        ),
        DelhiPlace(
            id = "plc_in_del_qutub_minar",
            name = "Qutub Minar, Mehrauli",
            area = "Seth Sarai, Mehrauli, South Delhi",
            category = "Landmark",
            lat = 28.5244,
            lng = 77.1855,
            stationId = "stn_in_del_hauz_khas_metro",
            aliases = listOf("Qutub Minar", "Mehrauli")
        ),
        DelhiPlace(
            id = "plc_in_del_vasant_kunj",
            name = "Vasant Kunj Ambience Mall",
            area = "Nelson Mandela Marg, Vasant Kunj, South West Delhi",
            category = "Locality",
            lat = 28.5402,
            lng = 77.1558,
            stationId = "stn_in_del_hauz_khas_metro",
            aliases = listOf("Vasant Kunj", "Ambience Mall Vasant Kunj")
        ),
        DelhiPlace(
            id = "plc_in_del_lajpat_gk",
            name = "Lajpat Nagar Central Market & Metro",
            area = "Lajpat Nagar, South Delhi",
            category = "Metro Station",
            lat = 28.5677,
            lng = 77.2433,
            stationId = "stn_in_del_hauz_khas_metro",
            aliases = listOf("Lajpat Nagar", "Central Market Lajpat Nagar")
        ),
        DelhiPlace(
            id = "plc_in_del_nehru_place",
            name = "Nehru Place & Kalkaji Mandir Metro",
            area = "Nehru Place, South Delhi",
            category = "Metro Station",
            lat = 28.5492,
            lng = 77.2519,
            stationId = "stn_in_del_hauz_khas_metro",
            aliases = listOf("Nehru Place", "Kalkaji Mandir")
        ),
        DelhiPlace(
            id = "plc_in_del_aiims",
            name = "AIIMS Delhi & Safdarjung Hospital",
            area = "Ansari Nagar, Aurobindo Marg, South Delhi",
            category = "Metro Station",
            lat = 28.5689,
            lng = 77.2075,
            stationId = "stn_in_del_hauz_khas_metro",
            aliases = listOf("AIIMS", "AIIMS Delhi", "Safdarjung Hospital")
        ),
        DelhiPlace(
            id = "stn_in_del_anand_vihar_metro",
            name = "Anand Vihar ISBT Metro Station",
            area = "Anand Vihar, East Delhi",
            category = "Metro Station",
            lat = 28.6469,
            lng = 77.3160,
            stationId = "stn_in_del_anand_vihar_metro",
            aliases = listOf("Anand Vihar Metro", "Anand Vihar ISBT")
        ),
        DelhiPlace(
            id = "plc_in_del_laxmi_mayur_vihar",
            name = "Laxmi Nagar Metro Station",
            area = "Vikas Marg, East Delhi",
            category = "Metro Station",
            lat = 28.6304,
            lng = 77.2777,
            stationId = "stn_in_del_anand_vihar_metro",
            aliases = listOf("Laxmi Nagar", "Preet Vihar")
        ),
        DelhiPlace(
            id = "plc_in_del_akshardham",
            name = "Swaminarayan Akshardham Temple, New Delhi",
            area = "Noida Mor, Pandav Nagar, East Delhi",
            category = "Landmark",
            lat = 28.6127,
            lng = 77.2773,
            stationId = "stn_in_del_anand_vihar_metro",
            aliases = listOf("Akshardham", "Akshardham Temple Delhi")
        ),
        DelhiPlace(
            id = "stn_in_del_anvt_rail",
            name = "Anand Vihar Terminal Railway Station (ANVT)",
            area = "Anand Vihar, East Delhi",
            category = "Railway Station",
            lat = 28.6482,
            lng = 77.3152,
            stationId = "stn_in_del_anvt_rail",
            aliases = listOf("ANVT", "Anand Vihar Terminal", "Anand Vihar Railway")
        ),
        DelhiPlace(
            id = "stn_in_del_anand_vihar_rrts",
            name = "Anand Vihar RRTS Station (Namo Bharat)",
            area = "Anand Vihar, East Delhi",
            category = "RRTS Station",
            lat = 28.6475,
            lng = 77.3168,
            stationId = "stn_in_del_anand_vihar_rrts",
            aliases = listOf("Anand Vihar Namo Bharat", "Anand Vihar RRTS")
        ),
        DelhiPlace(
            id = "stn_in_del_sahibabad_rrts",
            name = "Sahibabad RRTS Station",
            area = "Sahibabad, Ghaziabad",
            category = "RRTS Station",
            lat = 28.6710,
            lng = 77.4360,
            stationId = "stn_in_del_sahibabad_rrts",
            aliases = listOf("Sahibabad Namo Bharat", "Sahibabad RRTS")
        ),
        DelhiPlace(
            id = "plc_in_del_karol_bagh",
            name = "Karol Bagh Metro Station",
            area = "Pusa Road / Ajmal Khan Road, Central Delhi",
            category = "Metro Station",
            lat = 28.6440,
            lng = 77.1885,
            stationId = "stn_in_del_rajiv_chowk_metro",
            aliases = listOf("Karol Bagh", "Rajendra Place")
        ),
        DelhiPlace(
            id = "plc_in_del_janakpuri_rajouri",
            name = "Janakpuri West Metro Station",
            area = "Shivaji Marg, Janakpuri, West Delhi",
            category = "Metro Station",
            lat = 28.6289,
            lng = 77.0781,
            stationId = "plc_in_del_dwarka_sec_21",
            aliases = listOf("Janakpuri", "Janakpuri West")
        ),
        DelhiPlace(
            id = "plc_in_del_rajouri_garden",
            name = "Rajouri Garden Metro Interchange",
            area = "Najafgarh Road, Rajouri Garden, West Delhi",
            category = "Metro Station",
            lat = 28.6490,
            lng = 77.1225,
            stationId = "plc_in_del_dwarka_sec_21",
            aliases = listOf("Rajouri Garden", "Rajouri Garden Metro")
        ),
        DelhiPlace(
            id = "plc_in_del_dwarka_sec_21",
            name = "Dwarka Sector 21 Metro Station",
            area = "Sector 21, Dwarka, South West Delhi",
            category = "Metro Station",
            lat = 28.5522,
            lng = 77.0583,
            stationId = "plc_in_del_dwarka_sec_21",
            aliases = listOf("Dwarka Sector 21", "Dwarka Metro")
        ),
        DelhiPlace(
            id = "plc_in_del_india_gate",
            name = "India Gate & Kartavya Path",
            area = "Kartavya Path, Central Delhi",
            category = "Landmark",
            lat = 28.6129,
            lng = 77.2295,
            stationId = "stn_in_del_rajiv_chowk_metro",
            aliases = listOf("India Gate", "Kartavya Path", "Central Secretariat")
        ),
        DelhiPlace(
            id = "plc_in_del_chandni_chowk",
            name = "Chandni Chowk & Red Fort (Old Delhi)",
            area = "Chandni Chowk, Old Delhi",
            category = "Metro Station",
            lat = 28.6578,
            lng = 77.2301,
            stationId = "stn_in_del_kashmere_gate_metro",
            aliases = listOf("Chandni Chowk", "Red Fort", "Lal Qila", "Old Delhi Railway Station", "DLI")
        ),
        DelhiPlace(
            id = "plc_in_del_igi_t3",
            name = "IGI Airport Terminal 3 (DEL)",
            area = "Indira Gandhi International Airport, New Delhi",
            category = "Airport",
            lat = 28.5562,
            lng = 77.1000,
            stationId = "stn_in_del_new_delhi_metro",
            aliases = listOf("Delhi Airport", "Terminal 3", "IGI Airport", "DEL Airport", "Aerocity")
        ),

        // --- Gurugram & Faridabad ---
        DelhiPlace(
            id = "plc_in_del_cyber_hub",
            name = "DLF Cyber Hub, Gurugram",
            area = "DLF Phase 2, NH-48, Gurugram, Haryana",
            category = "Business Hub",
            lat = 28.4950,
            lng = 77.0895,
            stationId = "stn_in_del_millennium_city_centre_metro",
            aliases = listOf("Cyber City", "Cyber Hub", "DLF Cyber Hub", "Udyog Vihar")
        ),
        DelhiPlace(
            id = "stn_in_del_millennium_city_centre_metro",
            name = "Millennium City Centre Gurugram Metro (HUDA)",
            area = "Sector 29, Gurugram, Haryana",
            category = "Metro Station",
            lat = 28.4595,
            lng = 77.0726,
            stationId = "stn_in_del_millennium_city_centre_metro",
            aliases = listOf("HUDA City Centre", "Millennium City Centre", "Sector 29 Gurgaon")
        ),
        DelhiPlace(
            id = "plc_in_fbd_nit",
            name = "Bata Chowk Metro & NIT Faridabad",
            area = "Mathura Road (NH-19), Faridabad, Haryana",
            category = "Metro Station",
            lat = 28.3960,
            lng = 77.3136,
            stationId = "stn_in_del_hauz_khas_metro",
            aliases = listOf("NIT Faridabad", "Bata Chowk", "Faridabad")
        ),

        // --- Agra, Jaipur, Lucknow, Varanasi, Chandigarh, Amritsar ---
        DelhiPlace(
            id = "stn_in_agc_rail",
            name = "Agra Cantt Railway Station (AGC)",
            area = "Idgah Colony, Agra, Uttar Pradesh",
            category = "Railway Station",
            lat = 27.1578,
            lng = 77.9899,
            stationId = "stn_in_agc_rail",
            aliases = listOf("AGC", "Agra Cantt", "Agra Railway Station")
        ),
        DelhiPlace(
            id = "plc_in_agc_taj_mahal",
            name = "Taj Mahal, Agra",
            area = "Dharmapuri, Tajganj, Agra, Uttar Pradesh",
            category = "Landmark",
            lat = 27.1751,
            lng = 78.0421,
            stationId = "stn_in_agc_rail",
            aliases = listOf("Taj Mahal", "Tajganj Agra")
        ),
        DelhiPlace(
            id = "plc_in_agc_isbt",
            name = "ISBT Agra (Transport Nagar)",
            area = "NH-19, Transport Nagar, Agra, Uttar Pradesh",
            category = "Bus Stop",
            lat = 27.2072,
            lng = 77.9822,
            stationId = "stn_in_agc_rail",
            aliases = listOf("ISBT Agra", "Agra Bus Stand", "Sanjay Place Agra")
        ),
        DelhiPlace(
            id = "stn_in_jp_rail",
            name = "Jaipur Junction Railway Station (JP)",
            area = "Station Road, Gopalbari, Jaipur, Rajasthan",
            category = "Railway Station",
            lat = 26.9196,
            lng = 75.7878,
            stationId = "stn_in_jp_rail",
            aliases = listOf("Jaipur Junction", "JP Railway Station", "Sindhi Camp Jaipur")
        ),
        DelhiPlace(
            id = "plc_in_jp_hawa_mahal",
            name = "Hawa Mahal & Badi Choupad Metro, Jaipur",
            area = "Badi Choupad, Pink City, Jaipur, Rajasthan",
            category = "Landmark",
            lat = 26.9239,
            lng = 75.8267,
            stationId = "stn_in_jp_rail",
            aliases = listOf("Hawa Mahal", "Bapu Bazaar Jaipur", "Pink City Jaipur")
        ),
        DelhiPlace(
            id = "stn_in_lko_rail",
            name = "Lucknow Charbagh Railway Station (LKO)",
            area = "Charbagh, Lucknow, Uttar Pradesh",
            category = "Railway Station",
            lat = 26.8318,
            lng = 80.9191,
            stationId = "stn_in_lko_rail",
            aliases = listOf("Lucknow Charbagh", "Charbagh Station", "LKO")
        ),
        DelhiPlace(
            id = "plc_in_lko_gomti_nagar",
            name = "Gomti Nagar & Hazratganj, Lucknow",
            area = "Gomti Nagar, Lucknow, Uttar Pradesh",
            category = "Locality",
            lat = 26.8505,
            lng = 81.0005,
            stationId = "stn_in_lko_rail",
            aliases = listOf("Gomti Nagar", "Hazratganj Lucknow")
        ),
        DelhiPlace(
            id = "stn_in_bsb_rail",
            name = "Varanasi Junction (Cantt) Railway Station",
            area = "Chetganj, Varanasi, Uttar Pradesh",
            category = "Railway Station",
            lat = 25.3270,
            lng = 82.9863,
            stationId = "stn_in_bsb_rail",
            aliases = listOf("Varanasi Junction", "BSB", "Banaras Station")
        ),
        DelhiPlace(
            id = "plc_in_bsb_kashi_vishwanath",
            name = "Shri Kashi Vishwanath Temple & Dashashwamedh Ghat",
            area = "Lahori Tola, Godowlia, Varanasi, Uttar Pradesh",
            category = "Landmark",
            lat = 25.3109,
            lng = 83.0107,
            stationId = "stn_in_bsb_rail",
            aliases = listOf("Kashi Vishwanath", "Dashashwamedh Ghat", "Assi Ghat")
        ),
        DelhiPlace(
            id = "stn_in_cdg_rail",
            name = "Chandigarh Junction Railway Station (CDG)",
            area = "Daria, Chandigarh",
            category = "Railway Station",
            lat = 30.7016,
            lng = 76.8219,
            stationId = "stn_in_cdg_rail",
            aliases = listOf("Chandigarh Junction", "CDG Station")
        ),
        DelhiPlace(
            id = "plc_in_cdg_sec17",
            name = "Sector 17 ISBT & Plaza, Chandigarh",
            area = "Sector 17, Chandigarh",
            category = "Bus Stop",
            lat = 30.7415,
            lng = 76.7821,
            stationId = "stn_in_cdg_rail",
            aliases = listOf("Sector 17 Chandigarh", "ISBT 17 Chandigarh")
        ),
        DelhiPlace(
            id = "stn_in_asr_rail",
            name = "Amritsar Junction Railway Station (ASR)",
            area = "GT Road, Amritsar, Punjab",
            category = "Railway Station",
            lat = 31.6335,
            lng = 74.8672,
            stationId = "stn_in_asr_rail",
            aliases = listOf("Amritsar Junction", "ASR Station")
        ),
        DelhiPlace(
            id = "plc_in_asr_golden_temple",
            name = "Sri Harmandir Sahib (Golden Temple), Amritsar",
            area = "Golden Temple Road, Amritsar, Punjab",
            category = "Landmark",
            lat = 31.6200,
            lng = 74.8765,
            stationId = "stn_in_asr_rail",
            aliases = listOf("Golden Temple", "Harmandir Sahib")
        ),

        // --- Mumbai, Pune, Ahmedabad ---
        DelhiPlace(
            id = "stn_in_bom_csmt_rail",
            name = "Chhatrapati Shivaji Maharaj Terminus (CSMT)",
            area = "Fort, South Mumbai, Maharashtra",
            category = "Railway Station",
            lat = 18.9398,
            lng = 72.8355,
            stationId = "stn_in_bom_csmt_rail",
            aliases = listOf("CSMT", "Mumbai CSMT", "VT Station Mumbai")
        ),
        DelhiPlace(
            id = "plc_in_bom_bkc",
            name = "Bandra Kurla Complex (BKC) & Bandra Terminus",
            area = "Bandra East, Mumbai, Maharashtra",
            category = "Business Hub",
            lat = 19.0658,
            lng = 72.8681,
            stationId = "stn_in_bom_csmt_rail",
            aliases = listOf("Bandra", "BKC Mumbai", "Bandra Kurla Complex")
        ),
        DelhiPlace(
            id = "plc_in_bom_andheri",
            name = "Andheri Railway & Metro Station, Mumbai",
            area = "Andheri West / East, Mumbai, Maharashtra",
            category = "Metro Station",
            lat = 19.1197,
            lng = 72.8464,
            stationId = "stn_in_bom_csmt_rail",
            aliases = listOf("Andheri", "Andheri East", "Andheri West", "Andheri Station")
        ),
        DelhiPlace(
            id = "plc_in_bom_airport",
            name = "Chhatrapati Shivaji Maharaj International Airport (BOM)",
            area = "Andheri East / Santacruz, Mumbai, Maharashtra",
            category = "Airport",
            lat = 19.0896,
            lng = 72.8656,
            stationId = "stn_in_bom_csmt_rail",
            aliases = listOf("Mumbai Airport", "BOM", "T2 Mumbai")
        ),
        DelhiPlace(
            id = "stn_in_pune_rail",
            name = "Pune Junction Railway Station (PUNE)",
            area = "Agarkar Nagar, Pune, Maharashtra",
            category = "Railway Station",
            lat = 18.5289,
            lng = 73.8744,
            stationId = "stn_in_pune_rail",
            aliases = listOf("Pune Junction", "Pune Station")
        ),
        DelhiPlace(
            id = "plc_in_pune_hinjewadi",
            name = "Rajiv Gandhi Infotech Park, Hinjewadi, Pune",
            area = "Hinjewadi Phase 1, Pune, Maharashtra",
            category = "Business Hub",
            lat = 18.5913,
            lng = 73.7389,
            stationId = "stn_in_pune_rail",
            aliases = listOf("Hinjewadi", "Hinjewadi Phase 1")
        ),
        DelhiPlace(
            id = "stn_in_adi_rail",
            name = "Ahmedabad Junction Railway Station (Kalupur)",
            area = "Kalupur, Ahmedabad, Gujarat",
            category = "Railway Station",
            lat = 23.0268,
            lng = 72.6010,
            stationId = "stn_in_adi_rail",
            aliases = listOf("Ahmedabad Junction", "ADI", "Kalupur Station")
        ),

        // --- Bengaluru, Hyderabad, Chennai, Kolkata, Kochi, Bhopal, Patna ---
        DelhiPlace(
            id = "stn_in_blr_majestic_metro",
            name = "Nadaprabhu Kempegowda Station, Majestic (Metro)",
            area = "Majestic, Bengaluru, Karnataka",
            category = "Metro Station",
            lat = 12.9757,
            lng = 77.5728,
            stationId = "stn_in_blr_majestic_metro",
            aliases = listOf("Majestic Metro", "Majestic Bengaluru")
        ),
        DelhiPlace(
            id = "stn_in_blr_sbc_rail",
            name = "KSR Bengaluru City Junction (SBC)",
            area = "Majestic, Bengaluru, Karnataka",
            category = "Railway Station",
            lat = 12.9781,
            lng = 77.5696,
            stationId = "stn_in_blr_sbc_rail",
            aliases = listOf("SBC", "Bengaluru City Railway Station")
        ),
        DelhiPlace(
            id = "plc_in_blr_koramangala",
            name = "Koramangala & HSR Layout, Bengaluru",
            area = "80 Feet Road, Koramangala, Bengaluru",
            category = "Locality",
            lat = 12.9352,
            lng = 77.6245,
            stationId = "stn_in_blr_majestic_metro",
            aliases = listOf("Koramangala", "HSR Layout Bengaluru")
        ),
        DelhiPlace(
            id = "plc_in_blr_whitefield",
            name = "Whitefield (ITPL) Metro & Tech Park, Bengaluru",
            area = "Whitefield, Bengaluru, Karnataka",
            category = "Metro Station",
            lat = 12.9698,
            lng = 77.7500,
            stationId = "stn_in_blr_majestic_metro",
            aliases = listOf("Whitefield", "ITPL Bengaluru", "Whitefield Metro")
        ),
        DelhiPlace(
            id = "plc_in_blr_electronic_city",
            name = "Electronic City Phase 1, Bengaluru",
            area = "Hosur Road, Electronic City, Bengaluru",
            category = "Business Hub",
            lat = 12.8452,
            lng = 77.6602,
            stationId = "stn_in_blr_majestic_metro",
            aliases = listOf("Electronic City", "Electronic City Bengaluru")
        ),
        DelhiPlace(
            id = "plc_in_blr_airport",
            name = "Kempegowda International Airport Bengaluru (BLR)",
            area = "Devanahalli, Bengaluru, Karnataka",
            category = "Airport",
            lat = 13.1986,
            lng = 77.7066,
            stationId = "stn_in_blr_sbc_rail",
            aliases = listOf("Bengaluru Airport", "BLR", "Bangalore Airport")
        ),
        DelhiPlace(
            id = "stn_in_hyd_ameerpet_metro",
            name = "Ameerpet Metro Interchange, Hyderabad",
            area = "Ameerpet, Hyderabad, Telangana",
            category = "Metro Station",
            lat = 17.4356,
            lng = 78.4446,
            stationId = "stn_in_hyd_ameerpet_metro",
            aliases = listOf("Ameerpet Metro", "Secunderabad")
        ),
        DelhiPlace(
            id = "plc_in_hyd_hitec_city",
            name = "HITEC City & Raidurg Metro, Hyderabad",
            area = "Madhapur / Gachibowli, Hyderabad, Telangana",
            category = "Metro Station",
            lat = 17.4435,
            lng = 78.3772,
            stationId = "stn_in_hyd_ameerpet_metro",
            aliases = listOf("HITEC City", "Gachibowli", "Madhapur Hyderabad")
        ),
        DelhiPlace(
            id = "stn_in_maa_central_metro",
            name = "Puratchi Thalaivar Dr. M.G.R. Central (Chennai Central)",
            area = "Park Town, Chennai, Tamil Nadu",
            category = "Metro Station",
            lat = 13.0818,
            lng = 80.2729,
            stationId = "stn_in_maa_central_metro",
            aliases = listOf("Chennai Central", "MAS")
        ),
        DelhiPlace(
            id = "stn_in_ccu_howrah_rail",
            name = "Howrah Junction Railway Station (HWH)",
            area = "Howrah, Kolkata, West Bengal",
            category = "Railway Station",
            lat = 22.5839,
            lng = 88.3426,
            stationId = "stn_in_ccu_howrah_rail",
            aliases = listOf("Howrah Junction", "HWH", "Howrah Station")
        ),
        DelhiPlace(
            id = "stn_in_bpl_rkmp_rail",
            name = "Rani Kamlapati Railway Station (RKMP), Bhopal",
            area = "Habibganj, Bhopal, Madhya Pradesh",
            category = "Railway Station",
            lat = 23.2175,
            lng = 77.4388,
            stationId = "stn_in_bpl_rkmp_rail",
            aliases = listOf("Rani Kamlapati", "RKMP", "Habibganj Bhopal")
        ),
        DelhiPlace(
            id = "stn_in_pnbe_rail",
            name = "Patna Junction Railway Station (PNBE)",
            area = "Fraser Road, Patna, Bihar",
            category = "Railway Station",
            lat = 25.6033,
            lng = 85.1376,
            stationId = "stn_in_pnbe_rail",
            aliases = listOf("Patna Junction", "PNBE")
        ),
        DelhiPlace(
            id = "stn_in_cok_ers_rail",
            name = "Ernakulam Junction South (ERS), Kochi",
            area = "MG Road, Ernakulam, Kochi, Kerala",
            category = "Railway Station",
            lat = 9.9696,
            lng = 76.2909,
            stationId = "stn_in_cok_ers_rail",
            aliases = listOf("Ernakulam Junction", "ERS", "Kochi Railway Station")
        )
    )

    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    fun resolveNearestStationId(lat: Double, lng: Double, maxDistanceMeters: Double = 12000.0): String? {
        val canonicalOnly = places.filter { it.stationId != null }
        val nearest = canonicalOnly.minByOrNull { haversineMeters(lat, lng, it.lat, it.lng) }
            ?: return null
        val dist = haversineMeters(lat, lng, nearest.lat, nearest.lng)
        return if (dist <= maxDistanceMeters) nearest.stationId else null
    }

    private val GENERIC_PLACE_STOPWORDS = setOf(
        "railway", "station", "junction", "jn", "cantt", "central", "terminus",
        "metro", "bus", "stand", "stop", "isbt", "depot", "airport", "terminal",
        "rrts", "temple", "mandir", "masjid", "gurudwara", "church", "fort",
        "hospital", "college", "university", "institute", "school", "mall", "market",
        "sector", "block", "pocket", "tower", "phase", "gate", "road", "marg",
        "nagar", "vihar", "colony", "enclave", "extension", "ext", "park", "chowk",
        "city", "shri", "sri", "st", "dr", "india", "khand", "the"
    )

    private val CITY_NAMES = setOf(
        "delhi", "noida", "ghaziabad", "gurugram", "gurgaon", "faridabad",
        "mathura", "vrindavan", "agra", "jaipur", "lucknow", "varanasi",
        "chandigarh", "amritsar", "mumbai", "pune", "ahmedabad", "bengaluru",
        "bangalore", "hyderabad", "chennai", "kolkata", "bhopal", "indore",
        "patna", "kochi", "ernakulam", "uttar", "pradesh", "haryana", "maharashtra",
        "karnataka", "rajasthan", "punjab", "gujarat", "bihar", "kerala"
    )

    /**
     * Normalizes a place name for duplicate detection so variants like
     * `"Mathura Junction Railway Station"` and `"Mathura Junction"` map to the same canonical key.
     */
    fun normalizePlaceNameForDedup(name: String): String {
        return name.lowercase()
            .substringBefore(",")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(
                Regex("\\b(railway|station|metro|bus|stand|stop|isbt|junction|jn|terminal|terminus|interchange|shri|sri|the)\\b"),
                " "
            )
            .replace(Regex("[^a-z0-9 ]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Returns true if two `DelhiPlace` instances represent the exact same physical place or station.
     */
    fun isSamePhysicalPlace(a: DelhiPlace, b: DelhiPlace): Boolean {
        if (a.id == b.id) return true
        val dist = haversineMeters(a.lat, a.lng, b.lat, b.lng)
        if (dist < 40.0 && a.name.equals(b.name, ignoreCase = true)) return true
        val normA = normalizePlaceNameForDedup(a.name)
        val normB = normalizePlaceNameForDedup(b.name)
        return dist < 450.0 && normA.isNotEmpty() && normA == normB && a.category == b.category
    }

    private fun extractSpecificLandmarkTokens(query: String): List<String> {
        val tokens = query.lowercase()
            .split(" ", ",", "-", "/", "(", ")")
            .map { it.trim() }
            .filter { it.length >= 2 }
        return tokens.filter { it !in GENERIC_PLACE_STOPWORDS && it !in CITY_NAMES }
    }

    private fun isGenericCityCentroidResult(candidateName: String, specificLandmarkTokens: List<String>): Boolean {
        if (specificLandmarkTokens.isEmpty()) return false
        val candTokens = candidateName.lowercase()
            .substringBefore(",")
            .split(" ", "-", "/", "(", ")")
            .map { it.trim() }
            .filter { it.length >= 2 }
        if (candTokens.isNotEmpty() && candTokens.all { it in CITY_NAMES }) {
            return true
        }
        val matchesAnyLandmark = specificLandmarkTokens.any { token ->
            candidateName.lowercase().contains(token)
        }
        return !matchesAnyLandmark && candTokens.any { it in CITY_NAMES }
    }

    /**
     * Strict local search against the verified hub list.
     *
     * Rules:
     * 1. Never matches a place in another city just because it shares words like "Junction" or "Bus Stand".
     * 2. If the user specifies a landmark/sub-location inside a city (e.g., "Radhika Vihar Mathura" or
     *    "Keshav Nagar Mathura"), does NOT return "Mathura Junction" just because "Mathura" is in `area`;
     *    instead returns empty when `includeFallbackSynthesis = false` so the live multi-API geocoders
     *    (ArcGIS + Nominatim + Photon + Open-Meteo) locate the exact place coordinates.
     * 3. When `includeFallbackSynthesis = true` (offline fallback), applies a deterministic coordinate
     *    offset derived from the query hash so two custom sub-locations in the same city NEVER share
     *    identical `(lat, lng)` coordinates.
     */
    fun searchPlaces(
        query: String,
        maxResults: Int = 5,
        includeFallbackSynthesis: Boolean = true
    ): List<DelhiPlace> {
        val rawTrimmed = query.trim()
        val q = rawTrimmed.lowercase()
        if (q.isEmpty()) return places.take(maxResults)

        val tokens = q.split(" ", ",", "-", "/", "(", ")")
            .map { it.trim() }
            .filter { it.length >= 2 }
        val distinctiveTokens = tokens.filter { it !in GENERIC_PLACE_STOPWORDS }
        val specificLandmarkTokens = distinctiveTokens.filter { it !in CITY_NAMES }

        val scored = places.mapNotNull { place ->
            val nameLower = place.name.lowercase()
            val areaLower = place.area.lowercase()
            val aliasesLower = place.aliases.map { it.lowercase() }

            val matchingAlias = place.aliases.firstOrNull { it.lowercase() == q }
                ?: place.aliases.firstOrNull { it.lowercase().startsWith(q) }
                ?: place.aliases.firstOrNull { it.lowercase().contains(q) }

            val matchedDistinctiveCount = if (distinctiveTokens.isNotEmpty()) {
                distinctiveTokens.count { token ->
                    nameLower.contains(token) ||
                        areaLower.contains(token) ||
                        aliasesLower.any { a -> a.contains(token) }
                }
            } else 0

            if (distinctiveTokens.isNotEmpty() && matchedDistinctiveCount == 0) {
                return@mapNotNull null
            }

            // CRITICAL FIX: If the user typed specific landmark words (e.g. "Radhika", "BSA", "Keshav")
            // beyond just the city name, the static place MUST match those specific landmark words in its
            // name or aliases — never return a city's railway station just because `areaLower` has the city!
            if (specificLandmarkTokens.isNotEmpty()) {
                val matchedLandmarkCount = specificLandmarkTokens.count { token ->
                    nameLower.contains(token) || aliasesLower.any { a -> a.contains(token) }
                }
                if (matchedLandmarkCount < specificLandmarkTokens.size) {
                    return@mapNotNull null
                }
            }

            // If user also typed a transit type word ("bus", "rail", "metro", "airport"), check name/category
            val requestedTransitWords = tokens.filter { it in setOf("bus", "railway", "metro", "airport", "isbt", "rrts") }
            if (requestedTransitWords.isNotEmpty()) {
                val matchesRequestedTransit = requestedTransitWords.all { tw ->
                    nameLower.contains(tw) ||
                        place.category.lowercase().contains(tw) ||
                        aliasesLower.any { a -> a.contains(tw) }
                }
                if (!matchesRequestedTransit && specificLandmarkTokens.isEmpty()) {
                    return@mapNotNull null
                }
            }

            val matchedTokenCount = if (tokens.isNotEmpty()) {
                tokens.count { token ->
                    nameLower.contains(token) ||
                        areaLower.contains(token) ||
                        aliasesLower.any { a -> a.contains(token) }
                }
            } else 0

            val score = when {
                nameLower == q || (matchingAlias != null && matchingAlias.lowercase() == q) -> 100
                nameLower.startsWith(q) -> 95
                matchingAlias != null && matchingAlias.lowercase().startsWith(q) -> 90
                nameLower.contains(q) -> 85
                distinctiveTokens.isNotEmpty() && matchedDistinctiveCount == distinctiveTokens.size && matchedTokenCount == tokens.size -> 82
                matchingAlias != null && matchedDistinctiveCount == distinctiveTokens.size -> 78
                distinctiveTokens.isNotEmpty() && matchedDistinctiveCount == distinctiveTokens.size -> 70 + matchedTokenCount * 2
                else -> 0
            }

            if (score > 0) Pair(place, score) else null
        }

        val sorted = scored
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.id }
            .take(maxResults)

        if (sorted.isNotEmpty()) return sorted
        if (!includeFallbackSynthesis) return emptyList()

        return listOf(buildDeterministicFallbackPlace(rawTrimmed, distinctiveTokens))
    }

    /**
     * Synthesizes a fallback place when offline, using a deterministic radial offset (`0.9 km – 3.8 km`)
     * derived from the place name hash so two custom sub-locations in the same city NEVER share the exact
     * same `(lat, lng)` coordinates.
     */
    private fun buildDeterministicFallbackPlace(
        rawTrimmed: String,
        distinctiveTokens: List<String>
    ): DelhiPlace {
        val defaultAnchor = places.firstOrNull { place ->
            distinctiveTokens.any { token ->
                place.area.lowercase().contains(token) || place.name.lowercase().contains(token)
            }
        } ?: places.first { it.id == "stn_in_del_rajiv_chowk_metro" }

        val displayTitle = rawTrimmed.split(" ").filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.replaceFirstChar { ch -> ch.uppercaseChar() }
        }
        val hash = abs(rawTrimmed.lowercase().hashCode())
        val angleRad = Math.toRadians((hash % 360).toDouble())
        val radiusKm = 1.1 + ((hash / 360) % 28) * 0.1 // 1.1 km to 3.8 km deterministic spread
        val dLat = (radiusKm / 111.0) * sin(angleRad)
        val dLng = (radiusKm / (111.0 * cos(Math.toRadians(defaultAnchor.lat)).coerceAtLeast(0.4))) * cos(angleRad)

        return DelhiPlace(
            id = "custom_sub_${rawTrimmed.lowercase().hashCode().toUInt()}",
            name = displayTitle,
            area = defaultAnchor.area,
            category = "Sub-location",
            lat = ((defaultAnchor.lat + dLat) * 100000.0).toInt() / 100000.0,
            lng = ((defaultAnchor.lng + dLng) * 100000.0).toInt() / 100000.0,
            stationId = defaultAnchor.stationId
        )
    }

    /**
     * Resolves accurate coordinates for any custom or colliding place using the 4-API geocoding pipeline.
     */
    suspend fun resolveAccuratePlace(
        place: DelhiPlace,
        avoidLat: Double? = null,
        avoidLng: Double? = null
    ): DelhiPlace = withContext(Dispatchers.IO) {
        val needsLookup = place.id.startsWith("custom_sub_") ||
            (avoidLat != null && avoidLng != null && haversineMeters(place.lat, place.lng, avoidLat, avoidLng) < 45.0)
        if (!needsLookup) return@withContext place

        val searchQuery = if (place.area.isNotBlank() && !place.name.contains(place.area.substringBefore(","), ignoreCase = true)) {
            "${place.name}, ${place.area.substringBefore(",")}"
        } else {
            place.name
        }

        val candidates = runCatching {
            fetchPlacesFromBackend(searchQuery, maxResults = 5)
        }.getOrDefault(emptyList())

        val bestMatch = candidates.firstOrNull { candidate ->
            !candidate.id.startsWith("custom_sub_") &&
                (avoidLat == null || avoidLng == null || haversineMeters(candidate.lat, candidate.lng, avoidLat, avoidLng) >= 45.0)
        }

        if (bestMatch != null) {
            return@withContext bestMatch.copy(
                name = place.name.ifBlank { bestMatch.name }
            )
        }

        // If offline and colliding with avoidLat/avoidLng, apply deterministic separation
        if (avoidLat != null && avoidLng != null && haversineMeters(place.lat, place.lng, avoidLat, avoidLng) < 45.0) {
            val tokens = place.name.lowercase().split(" ").filter { it.length >= 2 }
            return@withContext buildDeterministicFallbackPlace(place.name, tokens)
        }

        place
    }

    /**
     * Queries 4 independent geocoding APIs in parallel for maximum Pan-India accuracy:
     * 1. ArcGIS World Geocoding Service REST API (`geocode.arcgis.com`)
     * 2. OpenStreetMap Nominatim Search API (`nominatim.openstreetmap.org`)
     * 3. Komoot Photon Geocoder API (`photon.komoot.io`)
     * 4. Open-Meteo Global Geocoding API (`geocoding-api.open-meteo.com`)
     */
    suspend fun fetchPlacesFromBackend(
        query: String,
        maxResults: Int = 6,
        lat: Double? = null,
        lng: Double? = null
    ): List<DelhiPlace> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        // 1. Optional local backend check
        if (backendReachable != false) {
            val backendResult = runCatching {
                fetchFromBackendServer(trimmed, maxResults, lat, lng)
            }.getOrNull()
            if (!backendResult.isNullOrEmpty()) {
                backendReachable = true
                return@withContext backendResult
            } else {
                backendReachable = false
            }
        }

        // 2. Query ArcGIS World Geocoder, Nominatim, Photon, and Open-Meteo in PARALLEL
        val (arcGisMatches, nominatimMatches, photonMatches, openMeteoMatches) = coroutineScope {
            val arcGisDeferred = async {
                runCatching { fetchFromArcGisDirect(trimmed, maxResults = maxResults, lat = lat, lng = lng) }
                    .getOrDefault(emptyList())
            }
            val nominatimDeferred = async {
                runCatching { fetchFromNominatimSearchDirect(trimmed, maxResults = maxResults, lat = lat, lng = lng) }
                    .getOrDefault(emptyList())
            }
            val photonDeferred = async {
                runCatching { fetchFromPhotonDirect(trimmed, maxResults = maxResults, lat = lat, lng = lng) }
                    .getOrDefault(emptyList())
            }
            val openMeteoDeferred = async {
                runCatching { fetchFromOpenMeteoDirect(trimmed, maxResults = maxResults) }
                    .getOrDefault(emptyList())
            }
            Quadruple(
                arcGisDeferred.await(),
                nominatimDeferred.await(),
                photonDeferred.await(),
                openMeteoDeferred.await()
            )
        }

        // 3. Strict local matches (without synthetic fallback)
        val localMatches = searchPlaces(trimmed, maxResults = maxResults, includeFallbackSynthesis = false)

        val combined = ArrayList<DelhiPlace>(maxResults * 2)

        // Add exact/prefix verified local hubs first
        for (item in localMatches) {
            if (
                item.name.equals(trimmed, ignoreCase = true) ||
                item.name.startsWith(trimmed, ignoreCase = true) ||
                item.aliases.any { it.equals(trimmed, ignoreCase = true) || it.startsWith(trimmed, ignoreCase = true) }
            ) {
                combined.add(item)
            }
        }

        // Merge ArcGIS + Nominatim + Photon + Open-Meteo + remaining local matches with canonical deduplication
        val allCandidates = arcGisMatches + nominatimMatches + photonMatches + localMatches + openMeteoMatches
        for (item in allCandidates) {
            if (combined.size >= maxResults) break
            val itemNorm = normalizePlaceNameForDedup(item.name)
            val isDuplicate = combined.any { existing ->
                val dist = haversineMeters(existing.lat, existing.lng, item.lat, item.lng)
                val existingNorm = normalizePlaceNameForDedup(existing.name)
                (existing.name.equals(item.name, ignoreCase = true) && dist < 450.0) ||
                    (dist < 450.0 && existingNorm.isNotEmpty() && existingNorm == itemNorm) ||
                    (dist < 140.0 && existing.name.substringBefore(",").trim().equals(item.name.substringBefore(",").trim(), ignoreCase = true))
            }
            if (!isDuplicate) {
                combined.add(item)
            }
        }

        if (combined.isNotEmpty()) {
            combined.take(maxResults)
        } else {
            searchPlaces(trimmed, maxResults = maxResults, includeFallbackSynthesis = true)
        }
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    /**
     * ArcGIS World Geocoding Service REST API (`findAddressCandidates`).
     * Public REST endpoint (no API key required for candidate lookup) with high-precision
     * street, landmark, temple, bus stand, college, and neighborhood coordinates across India.
     */
    private fun fetchFromArcGisDirect(
        trimmed: String,
        maxResults: Int,
        lat: Double? = null,
        lng: Double? = null
    ): List<DelhiPlace> {
        val specificLandmarkTokens = extractSpecificLandmarkTokens(trimmed)
        val encodedQ = URLEncoder.encode(trimmed, "UTF-8")
        val locationParam = if (lat != null && lng != null) "&location=$lng,$lat" else ""
        val urlStr =
            "$ARCGIS_FIND_URL?SingleLine=$encodedQ&countryCode=IND&maxLocations=10&outFields=Match_addr,PlaceName,Place_addr,City,Subregion,Region,Type&f=json$locationParam"

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2800
            readTimeout = 2800
            setRequestProperty("User-Agent", OSM_USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }

        if (conn.responseCode != 200) return emptyList()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates") ?: return emptyList()

        val results = ArrayList<DelhiPlace>(maxResults)
        for (i in 0 until candidates.length()) {
            val cand = candidates.optJSONObject(i) ?: continue
            val loc = cand.optJSONObject("location") ?: continue
            val pLng = loc.optDouble("x", Double.NaN)
            val pLat = loc.optDouble("y", Double.NaN)
            if (pLat.isNaN() || pLng.isNaN()) continue
            if (pLat !in 6.0..38.0 || pLng !in 68.0..98.0) continue

            val attrs = cand.optJSONObject("attributes")
            val matchAddr = attrs?.optString("Match_addr", "")?.ifBlank { cand.optString("address", "") } ?: ""
            val placeName = attrs?.optString("PlaceName", "")?.trim() ?: ""
            val placeAddr = attrs?.optString("Place_addr", "")?.trim() ?: ""
            val city = attrs?.optString("City", "")?.trim()
                ?.ifBlank { attrs.optString("Subregion", "").trim() } ?: ""
            val region = attrs?.optString("Region", "")?.trim() ?: ""
            val typeStr = attrs?.optString("Type", "")?.lowercase() ?: ""

            val name = when {
                placeName.isNotBlank() && city.isNotBlank() && !placeName.equals(city, ignoreCase = true) && !placeName.contains(city, ignoreCase = true) ->
                    "$placeName, $city"
                placeName.isNotBlank() -> placeName
                matchAddr.isNotBlank() -> matchAddr.substringBefore(",").trim()
                else -> continue
            }

            if (isGenericCityCentroidResult(name, specificLandmarkTokens)) continue

            val rawAreaParts = listOfNotNull(
                placeAddr.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) },
                city.takeIf { it.isNotBlank() && !name.equals(it, ignoreCase = true) },
                region.takeIf { it.isNotBlank() && !name.equals(it, ignoreCase = true) }
            ).distinct()
            val area = if (rawAreaParts.isNotEmpty()) {
                rawAreaParts.joinToString(", ")
            } else {
                matchAddr.substringAfter(",", "India").trim().ifBlank { "India" }
            }

            val lowerName = "$name $typeStr".lowercase()
            val category = when {
                lowerName.contains("rrts") || lowerName.contains("namo bharat") -> "RRTS Station"
                lowerName.contains("metro") || lowerName.contains("subway") -> "Metro Station"
                lowerName.contains("train") || lowerName.contains("railway") || lowerName.contains("junction") -> "Railway Station"
                lowerName.contains("bus") || lowerName.contains("isbt") -> "Bus Stop"
                lowerName.contains("airport") || lowerName.contains("aerodrome") -> "Airport"
                lowerName.contains("college") || lowerName.contains("university") || lowerName.contains("school") -> "University"
                lowerName.contains("temple") || lowerName.contains("mandir") || lowerName.contains("monument") -> "Landmark"
                else -> "Place"
            }

            val id = "arcgis_${i}_${(pLat * 10000).toInt()}_${(pLng * 10000).toInt()}"
            val isDup = results.any {
                haversineMeters(it.lat, it.lng, pLat, pLng) < 120.0 &&
                    it.name.substringBefore(",").equals(name.substringBefore(","), ignoreCase = true)
            }
            if (!isDup) {
                results.add(
                    DelhiPlace(
                        id = id,
                        name = name,
                        area = area,
                        category = category,
                        lat = pLat,
                        lng = pLng,
                        stationId = resolveNearestStationId(pLat, pLng, 8000.0)
                    )
                )
            }
            if (results.size >= maxResults) break
        }
        return results
    }

    /**
     * Open-Meteo Global Geocoding API for fast city, town, tehsil, and locality resolution in India.
     */
    private fun fetchFromOpenMeteoDirect(
        trimmed: String,
        maxResults: Int
    ): List<DelhiPlace> {
        val specificLandmarkTokens = extractSpecificLandmarkTokens(trimmed)
        val encodedQ = URLEncoder.encode(trimmed, "UTF-8")
        val urlStr = "$OPEN_METEO_GEOCODE_URL?name=$encodedQ&count=10&language=en&format=json"

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2500
            readTimeout = 2500
            setRequestProperty("User-Agent", OSM_USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }

        if (conn.responseCode != 200) return emptyList()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(body)
        val arr = root.optJSONArray("results") ?: return emptyList()

        val results = ArrayList<DelhiPlace>(maxResults)
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val countryCode = item.optString("country_code", "").uppercase()
            if (countryCode.isNotEmpty() && countryCode != "IN") continue

            val pLat = item.optDouble("latitude", Double.NaN)
            val pLng = item.optDouble("longitude", Double.NaN)
            if (pLat.isNaN() || pLng.isNaN()) continue

            val name = item.optString("name", "").trim()
            if (name.isEmpty()) continue
            if (isGenericCityCentroidResult(name, specificLandmarkTokens)) continue

            val admin3 = item.optString("admin3", "").trim()
            val admin2 = item.optString("admin2", "").trim()
            val admin1 = item.optString("admin1", "").trim()
            val areaParts = listOfNotNull(
                admin3.takeIf { it.isNotBlank() && !it.equals(name, true) },
                admin2.takeIf { it.isNotBlank() && !it.equals(name, true) },
                admin1.takeIf { it.isNotBlank() && !it.equals(name, true) }
            ).distinct()
            val area = if (areaParts.isNotEmpty()) areaParts.joinToString(", ") else "India"

            results.add(
                DelhiPlace(
                    id = "meteo_${item.optLong("id", i.toLong())}",
                    name = name,
                    area = area,
                    category = "Locality",
                    lat = pLat,
                    lng = pLng,
                    stationId = resolveNearestStationId(pLat, pLng, 8000.0)
                )
            )
            if (results.size >= maxResults) break
        }
        return results
    }

    private fun fetchFromBackendServer(
        trimmed: String,
        maxResults: Int,
        lat: Double?,
        lng: Double?
    ): List<DelhiPlace> {
        val encodedQ = URLEncoder.encode(trimmed, "UTF-8")
        val biasParams = if (lat != null && lng != null) "&lat=$lat&lng=$lng" else ""
        val urlStr = "$backendBaseUrl/api/places?q=$encodedQ$biasParams"

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 500
            readTimeout = 800
            setRequestProperty("Accept", "application/json")
        }

        val code = conn.responseCode
        if (code != 200) {
            throw IllegalStateException("Places search failed with HTTP $code")
        }

        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(body)
        val arr = root.optJSONArray("value") ?: return emptyList()

        val results = ArrayList<DelhiPlace>(maxResults)
        for (i in 0 until minOf(arr.length(), maxResults)) {
            val v = arr.optJSONObject(i) ?: continue
            val placeLat = v.optDouble("lat", Double.NaN)
            val placeLng = v.optDouble("lng", Double.NaN)
            if (placeLat.isNaN() || placeLng.isNaN()) continue
            val stationId = v.optString("stationId", "").ifEmpty {
                v.optJSONObject("stationHint")?.optString("stationId", "")?.ifEmpty {
                    v.optJSONObject("stationHint")?.optString("id", "")?.ifEmpty { null }
                }
            } ?: resolveNearestStationId(placeLat, placeLng, 5000.0)
            val area = v.optString("area", "").ifEmpty {
                v.optString("fullAddress", "India")
            }
            val category = v.optString("category", "").ifEmpty {
                v.optString("placeType", "Place")
            }
            results.add(
                DelhiPlace(
                    id = v.optString("id", "place_$i"),
                    name = v.optString("name", trimmed),
                    area = area,
                    category = category,
                    lat = placeLat,
                    lng = placeLng,
                    stationId = stationId
                )
            )
        }
        return results
    }

    private fun fetchFromPhotonDirect(
        trimmed: String,
        maxResults: Int,
        lat: Double?,
        lng: Double?
    ): List<DelhiPlace> {
        val specificLandmarkTokens = extractSpecificLandmarkTokens(trimmed)
        val encodedQ = URLEncoder.encode(trimmed, "UTF-8")
        val biasParams = if (lat != null && lng != null) "&lat=$lat&lon=$lng" else ""
        val urlStr = "$PHOTON_BASE_URL?q=$encodedQ&limit=12&lang=en$biasParams"

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2800
            readTimeout = 2800
            setRequestProperty("User-Agent", OSM_USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }

        if (conn.responseCode != 200) return emptyList()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(body)
        val features = root.optJSONArray("features") ?: return emptyList()

        val results = ArrayList<DelhiPlace>(maxResults)
        for (i in 0 until features.length()) {
            val feat = features.optJSONObject(i) ?: continue
            val props = feat.optJSONObject("properties") ?: continue
            val coords = feat.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue

            val country = props.optString("countrycode", "").uppercase()
            if (country.isNotEmpty() && country != "IN") continue

            val pLng = coords.optDouble(0, Double.NaN)
            val pLat = coords.optDouble(1, Double.NaN)
            if (pLat.isNaN() || pLng.isNaN()) continue
            if (pLat !in 6.0..38.0 || pLng !in 68.0..98.0) continue

            val name = props.optString("name", "")
                .ifEmpty { props.optString("locality", "") }
                .ifEmpty { props.optString("district", "") }
                .ifEmpty { props.optString("street", "") }
                .ifEmpty { props.optString("city", "") }
                .trim()
            if (name.isEmpty()) continue
            if (isGenericCityCentroidResult(name, specificLandmarkTokens)) continue

            val osmKey = props.optString("osm_key", "").lowercase()
            val osmVal = props.optString("osm_value", "").lowercase()
            val lowerName = name.lowercase()

            val category = when {
                lowerName.contains("rrts") || lowerName.contains("namo bharat") -> "RRTS Station"
                osmVal == "subway" || osmVal == "subway_entrance" || lowerName.contains("metro") -> "Metro Station"
                osmKey == "railway" || lowerName.contains("railway") || lowerName.contains("junction") -> "Railway Station"
                osmVal == "bus_station" || osmVal == "bus_stop" || lowerName.contains("isbt") || lowerName.contains("bus stand") -> "Bus Stop"
                osmKey == "aeroway" || lowerName.contains("airport") -> "Airport"
                osmVal == "suburb" || osmVal == "neighbourhood" || osmVal == "residential" || lowerName.contains("sector") -> "Sub-location"
                else -> "Place"
            }

            val areaParts = listOfNotNull(
                props.optString("locality", "").takeIf { it.isNotBlank() && !it.equals(name, true) },
                props.optString("street", "").takeIf { it.isNotBlank() && !it.equals(name, true) },
                props.optString("district", "").takeIf { it.isNotBlank() && !it.equals(name, true) },
                props.optString("city", "").takeIf { it.isNotBlank() && !it.equals(name, true) },
                props.optString("state", "").takeIf { it.isNotBlank() && !it.equals(name, true) }
            ).distinct()
            val area = if (areaParts.isNotEmpty()) areaParts.joinToString(", ") else "India"

            val nearestStationId = resolveNearestStationId(pLat, pLng, 8000.0)
            val osmId = props.optLong("osm_id", i.toLong())
            val id = "osm_${props.optString("osm_type", "N")}_$osmId"

            if (results.none { it.id == id || (it.name.equals(name, ignoreCase = true) && it.area.equals(area, ignoreCase = true)) }) {
                results.add(
                    DelhiPlace(
                        id = id,
                        name = name,
                        area = area,
                        category = category,
                        lat = pLat,
                        lng = pLng,
                        stationId = nearestStationId
                    )
                )
            }
            if (results.size >= maxResults) break
        }
        return results
    }

    private fun fetchFromNominatimSearchDirect(
        trimmed: String,
        maxResults: Int,
        lat: Double? = null,
        lng: Double? = null
    ): List<DelhiPlace> {
        val specificLandmarkTokens = extractSpecificLandmarkTokens(trimmed)
        val encodedQ = URLEncoder.encode(trimmed, "UTF-8")
        val viewboxParam = if (lat != null && lng != null) {
            "&viewbox=${lng - 0.5},${lat + 0.5},${lng + 0.5},${lat - 0.5}"
        } else ""
        val urlStr = "$NOMINATIM_SEARCH_URL?q=$encodedQ&countrycodes=in&format=jsonv2&addressdetails=1&limit=10&accept-language=en$viewboxParam"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2800
            readTimeout = 2800
            setRequestProperty("User-Agent", OSM_USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        if (conn.responseCode != 200) return emptyList()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONArray(body)
        val results = ArrayList<DelhiPlace>(maxResults)
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val pLat = item.optDouble("lat", Double.NaN)
            val pLng = item.optDouble("lon", Double.NaN)
            if (pLat.isNaN() || pLng.isNaN()) continue
            val displayName = item.optString("display_name", "")
            val rawName = item.optString("name", "").ifEmpty {
                displayName.substringBefore(",").trim()
            }
            if (rawName.isEmpty()) continue
            if (isGenericCityCentroidResult(rawName, specificLandmarkTokens)) continue
            val addr = item.optJSONObject("address")
            val areaParts = listOfNotNull(
                addr?.optString("neighbourhood", "")?.takeIf { it.isNotBlank() && !it.equals(rawName, true) },
                addr?.optString("suburb", "")?.takeIf { it.isNotBlank() && !it.equals(rawName, true) },
                addr?.optString("city", "")?.takeIf { it.isNotBlank() && !it.equals(rawName, true) }
                    ?: addr?.optString("town", "")?.takeIf { it.isNotBlank() }
                    ?: addr?.optString("state_district", "")?.takeIf { it.isNotBlank() },
                addr?.optString("state", "")?.takeIf { it.isNotBlank() }
            ).distinct()
            val area = if (areaParts.isNotEmpty()) areaParts.joinToString(", ") else "India"
            val typeStr = item.optString("type", "").lowercase()
            val category = when {
                typeStr.contains("station") || rawName.contains("Junction", true) -> "Railway Station"
                typeStr.contains("subway") || rawName.contains("Metro", true) -> "Metro Station"
                typeStr.contains("bus") || rawName.contains("Bus Stand", true) || rawName.contains("ISBT", true) -> "Bus Stop"
                typeStr.contains("aerodrome") || rawName.contains("Airport", true) -> "Airport"
                typeStr.contains("suburb") || typeStr.contains("neighbourhood") || typeStr.contains("residential") -> "Sub-location"
                else -> "Place"
            }
            val id = "nom_search_${item.optLong("place_id", i.toLong())}"
            results.add(
                DelhiPlace(
                    id = id,
                    name = rawName,
                    area = area,
                    category = category,
                    lat = pLat,
                    lng = pLng,
                    stationId = resolveNearestStationId(pLat, pLng, 8000.0)
                )
            )
            if (results.size >= maxResults) break
        }
        return results
    }

    suspend fun reverseGeocodeFromBackend(lat: Double, lng: Double): DelhiPlace? =
        withContext(Dispatchers.IO) {
            if (backendReachable != false) {
                val backendPlace = runCatching {
                    val urlStr = "$backendBaseUrl/api/places/reverse?lat=$lat&lng=$lng"
                    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 500
                        readTimeout = 800
                        setRequestProperty("Accept", "application/json")
                    }
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val root = JSONObject(body)
                        val v = root.optJSONObject("value")
                        if (v != null) {
                            val placeLat = v.optDouble("lat", lat)
                            val placeLng = v.optDouble("lng", lng)
                            val stationId = v.optString("stationId", "").ifEmpty {
                                v.optJSONObject("stationHint")?.optString("stationId", "")?.ifEmpty { null }
                            } ?: resolveNearestStationId(placeLat, placeLng, 5000.0)
                            DelhiPlace(
                                id = v.optString("id", "rev_${lat}_$lng"),
                                name = v.optString("name", "Current Location"),
                                area = v.optString("area", "").ifEmpty { v.optString("fullAddress", "India") },
                                category = v.optString("category", "").ifEmpty { v.optString("placeType", "Place") },
                                lat = placeLat,
                                lng = placeLng,
                                stationId = stationId
                            )
                        } else null
                    } else null
                }.getOrNull()

                if (backendPlace != null) {
                    backendReachable = true
                    return@withContext backendPlace
                } else {
                    backendReachable = false
                }
            }

            val nominatimPlace = runCatching {
                val urlStr = "$NOMINATIM_REVERSE_URL?lat=$lat&lon=$lng&format=jsonv2&addressdetails=1&accept-language=en"
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2500
                    readTimeout = 2500
                    setRequestProperty("User-Agent", OSM_USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val item = JSONObject(body)
                    val addr = item.optJSONObject("address")
                    val name = item.optString("name", "").trim()
                        .ifEmpty { addr?.optString("suburb", "") ?: "" }
                        .ifEmpty { addr?.optString("road", "") ?: "" }
                        .ifEmpty { "Current GPS Location" }
                    val city = addr?.optString("city", "")
                        ?.ifEmpty { addr.optString("state_district", "India") }
                        ?: "India"
                    DelhiPlace(
                        id = "nom_${item.optLong("place_id", System.currentTimeMillis())}",
                        name = name,
                        area = city,
                        category = "Location",
                        lat = item.optDouble("lat", lat),
                        lng = item.optDouble("lon", lng),
                        stationId = resolveNearestStationId(lat, lng, 8000.0)
                    )
                } else null
            }.getOrNull()

            if (nominatimPlace != null) return@withContext nominatimPlace

            places.minByOrNull { haversineMeters(lat, lng, it.lat, it.lng) }
        }
}
