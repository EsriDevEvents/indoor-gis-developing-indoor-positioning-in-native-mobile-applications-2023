package com.esri.ipsexample.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arcgismaps.ArcGISException
import com.arcgismaps.Guid
import com.arcgismaps.LoadStatus
import com.arcgismaps.data.*
import com.arcgismaps.location.IndoorsLocationDataSource
import com.arcgismaps.location.Location
import com.arcgismaps.location.LocationDataSourceStatus
import com.arcgismaps.mapping.ArcGISMap
import com.arcgismaps.mapping.layers.FeatureLayer
import com.arcgismaps.portal.Portal
import com.arcgismaps.portal.PortalItem
import com.esri.ipsexample.R
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    val portalURL = "https://viennardc.maps.arcgis.com"
    private var indoorsLocationDataSource: IndoorsLocationDataSource? = null

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _locationDetailsState = MutableStateFlow<LocationDetailsState?>(null)
    val locationDetailsState: StateFlow<LocationDetailsState?> = _locationDetailsState.asStateFlow()

    fun connectToPortal() {
        viewModelScope.launch {
            _uiState.update { currentUiState -> currentUiState.copy(showProgressBar = true) }

            val portal = Portal(portalURL, Portal.Connection.Authenticated)
            portal.load()
                .onSuccess {
                    val arcGisMap =
                        ArcGISMap(PortalItem(portal, "a4ab11d9eca94692acff9580ae47a9dc"))
                    arcGisMap.load()
                        .onSuccess {
                            _uiState.update { currentUiState -> currentUiState.copy(map = arcGisMap) }
                            // Load all necessary tables and start ILDS
                            loadMapDataAndStartIndoorsLocationDataSource(arcGisMap)
                        }
                        .onFailure {
                            presentError(R.string.error_map_loading)
                        }
                }
                .onFailure {
                    Log.d("MainViewModel", "Error: ${(it as? ArcGISException)?.additionalMessage}")
                    presentError(R.string.error_portal_loading)
                }
        }
    }

    private suspend fun loadMapDataAndStartIndoorsLocationDataSource(arcGISMap: ArcGISMap) {
        if (loadLocationSourceFeatureTables(arcGISMap) != LoadStatus.Loaded) {
            presentError(R.string.error_load_ips_tables)
            return
        }

        loadIndoorsLocationDataSource()
    }

    private suspend fun loadIndoorsLocationDataSource() {
        val positioningTable = getIPSPositioningTable()
        if (positioningTable == null) {
            presentError(R.string.error_no_ips_supported)  // no positioningTable available
            return
        }

        try {
            val queryParameters = QueryParameters()
            queryParameters.maxFeatures = 1
            queryParameters.whereClause = "1 = 1"
            val orderByFields = queryParameters.orderByFields
            val dateCreatedFieldName = getDateCreatedFieldName(positioningTable.fields)
            orderByFields.add(OrderBy(dateCreatedFieldName, sortOrder = SortOrder.Descending))
            positioningTable.queryFeatures(queryParameters)
                .onSuccess {
                    val feature = it.firstOrNull()
                    if (feature != null) {
                        // The ID that identifies a row in the positioning table.
                        val positioningId =
                            feature.attributes[getGlobalIdFieldName(positioningTable)] as Guid
                        // Setting up IndoorsLocationDataSource with positioning, pathways tables and positioning ID.
                        // positioningTable - the "ips_positioning" feature table from an IPS-enabled map.
                        // pathwaysTable - An ArcGISFeatureTable that contains pathways as per the ArcGIS Indoors Information Model.
                        //   Setting this property enables path snapping of locations provided by the IndoorsLocationDataSource.
                        // positioningID - an ID which identifies a specific row in the positioningTable that should be used for setting up IPS.
                        indoorsLocationDataSource = setupIndoorsLocationDataSource(
                            positioningTable,
                            getServiceFeatureTable("Pathways") as? ArcGISFeatureTable,
                            positioningId
                        )
                        _uiState.update { currentUiState ->
                            currentUiState.copy(
                                mapState = MapState.MAP_LOADED,
                                indoorsLocationDataSource = indoorsLocationDataSource,
                                showProgressBar = false,
                                errorString = null
                            )
                        }
                    } else {
                        presentError(R.string.error_no_ips_positioning)  // positioningTable has no entries
                    }
                }
                .onFailure {
                    presentError(R.string.error_no_ips_positioning)  // positioningTable has no entries
                }
        } catch (exception: Exception) {
            presentError(R.string.error_ips_positioning_id)
        }
    }

    private fun setupIndoorsLocationDataSource(
        positioningTable: FeatureTable,
        pathwaysFeatureTable: ArcGISFeatureTable?,
        positioningId: Guid?
    ): IndoorsLocationDataSource {
        val indoorsLocationDataSource = IndoorsLocationDataSource(
            positioningTable = positioningTable,
            pathwaysTable = pathwaysFeatureTable,
            positioningId = positioningId
        )
        indoorsLocationDataSource.locationChanged.onEach { updateUI(it) }.launchIn(viewModelScope)

        indoorsLocationDataSource.status.drop(1).onEach { status ->
            when (status) {
                LocationDataSourceStatus.Starting -> {
                    handleILDSStatusUpdate(MapState.ILDS_STARTING)
                }
                LocationDataSourceStatus.Started -> {
                    handleILDSStatusUpdate(MapState.ILDS_STARTED)
                }
                LocationDataSourceStatus.FailedToStart -> {
                    handleILDSStatusUpdate(
                        MapState.ILDS_FAILED_TO_START,
                        R.string.error_ilds_failed_to_start
                    )
                }
                LocationDataSourceStatus.Stopped -> {
                    val error = indoorsLocationDataSource.error.value as? ArcGISException
                    Log.d("MainViewModel", "error: ${error?.additionalMessage}")
                    handleILDSStatusUpdate(
                        MapState.ILDS_STOPPED, if (error != null) {
                            R.string.error_ilds_stopped
                        } else {
                            null
                        }
                    )
                    _locationDetailsState.update { currentState -> currentState?.copy(isVisible = false) }
                }
                else -> {}
            }
        }.launchIn(viewModelScope)

        return indoorsLocationDataSource
    }

    fun startIndoorsLocationDataSource() {
        viewModelScope.launch {
            _uiState.update { currentUiState -> currentUiState.copy(startStopButtonVisibility = false) }
            indoorsLocationDataSource?.start() ?: kotlin.run {
                _uiState.value.map?.let {
                    loadMapDataAndStartIndoorsLocationDataSource(it)
                }
            }
        }
    }

    fun stopIndoorsLocationDataSource() {
        viewModelScope.launch {
            indoorsLocationDataSource?.stop()
        }
    }

    private fun updateUI(location: Location) {
        val floor =
            location.additionalSourceProperties[Location.SourceProperties.Keys.FLOOR] as? Int
        val positionSource =
            location.additionalSourceProperties[Location.SourceProperties.Keys.POSITION_SOURCE] as? String
        val transmitterCount =
            location.additionalSourceProperties["transmitterCount"] as? Int
        val networkCount =
            location.additionalSourceProperties[Location.SourceProperties.Keys.SATELLITE_COUNT] as? Int

        _locationDetailsState.value = LocationDetailsState(
            floor = floor,
            positionSourceText = positionSource,
            horizontalAccuracyText = location.horizontalAccuracy,
            senderCount = if (positionSource == Location.SourceProperties.Values.POSITION_SOURCE_GNSS) {
                networkCount
            } else {
                transmitterCount
            }
        )
    }

    private suspend fun loadLocationSourceFeatureTables(agsMap: ArcGISMap): LoadStatus {
        return try {
            // Load positioning table
            agsMap.tables.forEach { table ->
                table.load()
            }
            LoadStatus.Loaded
        } catch (e: Exception) {
            Log.d(
                "MainViewModel",
                "Fails to load positioning table: ${e.cause?.localizedMessage}"
            )
            LoadStatus.FailedToLoad(e)
        }
    }

    private fun getGlobalIdFieldName(positioningTable: FeatureTable): String {
        return when (positioningTable) {
            is ServiceFeatureTable -> positioningTable.globalIdField
            is GeodatabaseFeatureTable -> positioningTable.globalIdField
            else -> "GlobalID"
        }
    }

    private fun getServiceFeatureTable(tableName: String): FeatureTable? {
        val map = _uiState.value.map
        val featureLayer =
            map?.operationalLayers?.filter { opLayer -> opLayer.name == tableName }
                ?.map { opLayer -> opLayer as FeatureLayer }?.firstOrNull()

        return featureLayer?.featureTable
    }

    private fun getIPSPositioningTable(): FeatureTable? {
        val map = _uiState.value.map
        return map?.tables?.find { table -> table.tableName.equals("ips_positioning", true) }
    }

    private fun getDateCreatedFieldName(fields: List<Field>): String {
        val field = fields
            .firstOrNull { field ->
                field.name.equals("DateCreated", ignoreCase = true)
                        || field.name.equals("Date_Created", ignoreCase = true)
            }
        return field?.name ?: ""
    }

    private fun handleILDSStatusUpdate(mapState: MapState, errorString: Int? = null) {
        _uiState.update { currentUiState ->
            currentUiState.copy(
                mapState = if (mapState == MapState.ILDS_FAILED_TO_START) {
                    MapState.MAP_LOADED
                } else {
                    mapState
                },
                startStopButtonText = if (mapState == MapState.ILDS_STARTED) {
                    R.string.stopILDSButton
                } else {
                    R.string.startILDSButton
                },
                startStopButtonVisibility = mapState != MapState.ILDS_STARTING,
                showProgressBar = mapState == MapState.ILDS_STARTING,
                errorString = errorString
            )
        }
    }

    private fun presentError(stringRes: Int?) {
        _uiState.update { currentUiState ->
            currentUiState.copy(
                showProgressBar = false,
                errorString = stringRes
            )
        }
    }
}

enum class MapState {
    INIT,
    MAP_LOADED,
    ILDS_STARTING,
    ILDS_STARTED,
    ILDS_FAILED_TO_START,
    ILDS_STOPPED
}

data class UiState(
    val mapState: MapState = MapState.INIT,
    val map: ArcGISMap? = null,
    val indoorsLocationDataSource: IndoorsLocationDataSource? = null,
    val errorString: Int? = null,
    val showProgressBar: Boolean = false,
    val startStopButtonText: Int? = null,
    val startStopButtonVisibility: Boolean = false
)

data class LocationDetailsState(
    val floor: Int?,
    val positionSourceText: String?,
    val horizontalAccuracyText: Double,
    val senderCount: Int?,
    val isVisible: Boolean = true
)
