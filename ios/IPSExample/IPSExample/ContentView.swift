//
// COPYRIGHT 2023 ESRI
//
// TRADE SECRETS: ESRI PROPRIETARY AND CONFIDENTIAL
// Unpublished material - all rights reserved under the
// Copyright Laws of the United States and applicable international
// laws, treaties, and conventions.
//
// For additional information, contact:
// Environmental Systems Research Institute, Inc.
// Attn: Contracts and Legal Services Department
// 380 New York Street
// Redlands, California, 92373
// USA
//
// email: contracts@esri.com
//

import SwiftUI
import ArcGIS
import CoreLocation

struct ContentView: View, AuthenticationChallengeHandler {
    private struct LoadedMap {
        let mapView: MapView
        let ilds: IndoorsLocationDataSource
    }
    
    @State private var mapLoadResult: Result<LoadedMap, Error>?
    @State private var info: String = ""
    @State private var startStopTitle: String = "Loading..."
    
    var body: some View {
        Group {
            ZStack {
                /// Map view
                if let mapLoadResult = mapLoadResult {
                    switch mapLoadResult {
                    case .success(let loadedMap):
                        loadedMap.mapView
                    case .failure(let error):
                        Text("Error loading map: \(errorString(for: error))")
                            .padding()
                    }
                } else {
                    ProgressView()
                }
                
                /// Location information view
                Text(info)
                    .font(.title3)
                    .background(.gray)
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .padding(EdgeInsets(top: 10, leading: 10, bottom: 0, trailing: 10))
                
                /// Start-Stop Button
                Button(action: {
                    if let mapLoadResult = mapLoadResult {
                        switch mapLoadResult {
                        case .failure:
                            break
                            
                        case let .success(loadedMap):
                            switch loadedMap.ilds.status {
                            case .starting, .started:
                                Task {
                                    await loadedMap.ilds.stop()
                                    startStopTitle = "start"
                                }
                                
                            case .failedToStart, .stopped, .stopping:
                                Task {
                                    try? await loadedMap.ilds.start()
                                    startStopTitle = "stop"
                                }
                            @unknown default: break
                            }
                        }
                    }
                }) {
                    Text(startStopTitle)
                }
                .font(.title)
                .background(.blue)
                .foregroundColor(.white)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                .padding(EdgeInsets(top: 0, leading: 10, bottom: 60, trailing: 10))
            }
        }.task {
            /// View shall run this task, before it is shown:
            do {
                let portal = Portal(url: URL(string: "https://viennardc.maps.arcgis.com")!)
                /// Load ArcGIS portal
                try await portal.load()
                
                let portalItem = PortalItem(portal: portal, id: Item.ID(rawValue: "3ed5dc18d2cb490b8316aa93bf98fe74")!)
                let map = Map(item: portalItem)
                /// Load specified map
                try await map.load()

                /// Load and select 'IPS Positioning' table from loaded map
                try await ArcGISUtils.load(loadables: map.tables)
                guard let positioningTable = map.tables.first(where: {$0.tableName == "IPS_Positioning"}) else {
                    throw SetupError.positioningTableNotFound
                }
                
                /// Select Pathways feature layer from operational layers inside the map
                let pathwaysTable = getFeatureLayer(name: "Pathways", in: map)!.featureTable as! ArcGISFeatureTable
                
                /// Initialize IndoorsLocationDataSource with the positioning and the pathways table
                let ilds = IndoorsLocationDataSource(positioningTable: positioningTable, pathwaysTable: pathwaysTable)
                
                /// Assign ILDS to the map's location display
                let locationDisplay = LocationDisplay(dataSource: ilds)
                let mapView = MapView(map: map).locationDisplay(locationDisplay)

                Task {
                    /// Subscribe to the streamed status updates
                    for await s in ilds.$status {
                        print("--- ILDS status changed to new status '\(s)' ---")
                    }
                }
                
                /// Start ILDS automatically upon starting the app
                try await locationDisplay.dataSource.start()
                /// Update UI
                startStopTitle = "stop"
                mapLoadResult = .success(LoadedMap(mapView: mapView, ilds: ilds))
                
                Task {
                    /// Subscribe to the locations stream
                    for await location in ilds.locations {
                        var info = ""
                        
                        /// Parse new location details from additionslSourceProperties
                        let positionSource = location.additionalSourceProperties[.positionSource] as! String
                        info += "Source: \(positionSource)"
                        
                        info += "\nFloor: "
                        if let floor = location.additionalSourceProperties[.floor] as? NSNumber {
                            info += "\(floor.intValue)"
                        } else {
                            info += "unknown"
                        }
                        
                        if positionSource == "GNSS", let satteliteCount = location.additionalSourceProperties[.satelliteCount] {
                            info += "\nSatellites: \(satteliteCount)"
                        } else if let transmitterCount = location.additionalSourceProperties[Location.SourcePropertyKey(rawValue: "transmitterCount")] {
                            info += "\nTransmitters: \(transmitterCount)"
                        }
                        
                        let fmt = NumberFormatter()
                        fmt.numberStyle = .decimal
                        fmt.maximumSignificantDigits = 5
                        if location.horizontalAccuracy != 0.0 {
                            info += "\nHoriz. Accuracy: \(fmt.string(for: location.horizontalAccuracy)!)"
                        }
                        
                        /// Show updated location information
                        self.info = info
                    }
                }
            } catch let error {
                mapLoadResult = .failure(error)
                startStopTitle = "failed"
            }
        }.onAppear {
            ArcGISEnvironment.authenticationChallengeHandler = self
            CLLocationManager().requestWhenInUseAuthorization()
        }
        .onDisappear {
            ArcGISEnvironment.authenticationChallengeHandler = nil
        }
    }
    
    private enum SetupError: LocalizedError {
        case failedToLoadIPS
        case noIPSDataFound
        case mapDoesNotSupportIPS
        case failedToLoadFeatureTables
        case positioningTableNotFound

        var errorDescription: String? {
            switch self {
            case .failedToLoadIPS:
                return NSLocalizedString("Failed to load IPS", comment: "")
            case .noIPSDataFound:
                return NSLocalizedString("No IPS data found", comment: "")
            case .mapDoesNotSupportIPS:
                return NSLocalizedString("Map does not support IPS", comment: "")
            case .failedToLoadFeatureTables:
                return NSLocalizedString("Failed to load feature tables", comment: "")
            case .positioningTableNotFound:
                return NSLocalizedString("Positioning table not found", comment: "")
            }
        }
    }
    
    private func errorString(for error: Error) -> String {
        switch error {
        case let authenticationError as ArcGISAuthenticationChallenge.Error:
            switch authenticationError {
            case .userCancelled(_):
                return "User cancelled error"
            case .credentialCannotBeShared:
                return "Provided credential cannot be shared"
            @unknown default:
                return "Generic error"
            }
        default:
            return error.localizedDescription
        }
    }
    
    func handleArcGISAuthenticationChallenge(_ challenge: ArcGISAuthenticationChallenge) async throws -> ArcGISAuthenticationChallenge.Disposition {
        return .useCredential(
            try await .token(challenge: challenge, username: "conf_user_IPS", password: "conf_user_IPS1")
        )
    }
    
    public func getFeatureLayer(name: String, in map: Map) -> FeatureLayer? {
        func getFeatureLayerFromOperationalLayers(layers: Array<Layer>) -> FeatureLayer? {
            for layer in layers {
                switch layer {
                case let featureLayer as FeatureLayer where featureLayer.name == name:
                    return featureLayer
                
                case let groupLayer as GroupLayer:
                    if let featureLayer = getFeatureLayerFromOperationalLayers(layers: groupLayer.layers) {
                        return featureLayer
                    }
                    
                default:
                    break
                }
            }
            
            return nil
        }
        
        return getFeatureLayerFromOperationalLayers(layers: map.operationalLayers)
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
