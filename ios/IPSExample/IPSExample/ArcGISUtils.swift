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

import Foundation
import ArcGIS

public struct ArcGISUtils {
    enum LoadError: Error {
        case noInternet
        case genericError
    }
    
    static func load(loadables: [Loadable]) async throws {
        guard let loadable = loadables.last else {
            return // finished
        }

        guard loadable.loadStatus != .loaded else {
            return try await self.load(loadables: loadables.dropLast())
        }

        do {
            try await loadable.retryLoad()
        } catch let error {
            if (error as NSError).isNoInternetError {
                throw LoadError.noInternet
            } else {
                throw LoadError.genericError
            }
        }

        return try await self.load(loadables: loadables.dropLast())
    }
}
