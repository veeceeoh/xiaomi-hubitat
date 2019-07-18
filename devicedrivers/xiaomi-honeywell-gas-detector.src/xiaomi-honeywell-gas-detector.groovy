/**
 *  Xiaomi MiJia Honeywell Gas Detector - model JTQJ-BF-01LM/BW
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.1b BETA
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  This code was adapted from a SmartThings device handler by foz333, based on work by KennethEvers
 *  Contributions to code by alecm, alixjg, bspranger, gn0st1c, Inpier, foz333, jmagnuson, KennethEvers, mike.maxwell, rinkek, ronvandegraaf, snalee, tmleaf, veeceeoh
 *
 *  Useful Links:
 *  zigbee2mqtt forum thread... https://github.com/Koenkk/zigbee2mqtt/issues/276
 *
 *  How to pair the gas detector to your Hubitat hub:
 *  1. Plug the detector into mains power
 *  2. Wait about 5 minutes while the device performs reset sequence:
 *     a. Clear -> Gas -> Alarm -> Clear -> Self-test alarm -> Clear
 *     b. LED flases green / red while detector warms up
 *     c. LED changes to solid greed, indicating the device is ready to detect gas
 *  3. Start Zigbee discovery mode on your Hubitat hub
 *  4. Press the gas detector's button 3 times quickly
 *  5. The gas detector device should appear in the Hubitat web UI, ready to rename
 *     If the device does not appear, try pressing the button 3 times again
 *
 *  Known issues:
 *  + Xiaomi devices send reports based on changes, and a status report every 50-60 minutes. These settings cannot be adjusted.
 *  + Pairing Xiaomi devices can be difficult as they were not designed to use with a Hubitat hub.
 *  + The connection can be dropped without warning. To reconnect, put Hubitat in Zigbee discovery mode, and follow the pairing procedure.
 *  + Dropped connections are often due to Xiaomi / Aqara device's incompatibility with most mains-powered Zigbee repeater devices
 *    For more information see this Hubitat Community Forums thread:  https://community.hubitat.com/t/xiaomi-devices-are-they-pairing-staying-connected-for-you
 *
 */

metadata {
	definition (name: "Xiaomi Gas Detector", namespace: "veeceeoh", author: "veeceeoh") {
		capability "Battery"
		capability "Configuration"
		capability "Sensor"
		capability "Smoke Detector"
		capability "TestCapability"
		// This driver uses the Smoke Detector capabilty because "Gas Detector" is not available
		// attributes: smoke ("detected","clear","tested")

		command "checkSensitivityLevel"
		command "resetBatteryReplacedDate"
		command "resetToClear"
		command "test"

		attribute "batteryLastReplaced", "String"
		attribute "lastCheckinTime", "String"
		attribute "lastCheckinEpoch", "String"
		attribute "lastClearTime", "String"
		attribute "lastClearEpoch", "String"
		attribute "lastDetectedTime", "String"
		attribute "lastDetectedEpoch", "String"
		attribute "lastTestedTime", "String"
		attribute "lastTestedEpoch", "String"

		fingerprint endpointId: "01", profileID: "0104", deviceID: "0101", inClusters: "0000,0004,0003,0001,0002,000A,0500", outClusters: "0019,000A", manufacturer: "LUMI", model: "lumi.sensor_natgas"
	}

	preferences {
		// Smoke Sensitivity Level Config
		// BETA: Need to confirm correct payload strings, as smoke detector payload
		input name: "sensLevel", type: "enum", title: "Gas sensitivity level", description: "", options: [[0x02010000:"High"],[0x02020000:"Medium"],[0x02030000:"Low"]], defaultValue: 0x02010000, required: true
		//Date/Time Stamp Events Config
		input name: "lastCheckinEnable", type: "bool", title: "Enable custom date/time stamp events for lastCheckin", description: ""
		input name: "otherCheckinEnable", type: "bool", title: "Enable custom date/time stamp events for lastClear, lastDetected, and lastTested", description: ""
		//Logging Message Config
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
		//Firmware 2.0.5 Compatibility Fix Config
		input name: "oldFirmware", type: "bool", title: "DISABLE 2.0.5 firmware compatibility fix (for users of 2.0.4 or earlier)", description: ""
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	displayDebugLog("Parsing message: ${description}")
	def result
	if (description?.startsWith('enroll request')) {
		List cmds = zigbee.enrollResponse()
		displayDebugLog("Zigbee IAS Enroll response: $cmds")
		result = cmds?.collect {new hubitat.device.HubAction(it)}
	} else {
		if (description?.startsWith('zone status')) {
			// Parse smoke - clear / detected / tested status report
			result = parseZoneStatusMessage(Integer.parseInt(description[17]))
		} else if (description?.startsWith('re')) {
			description = description - "read attr - "
			Map descMap = (description).split(",").inject([:]) {
				map, param ->
				def nameAndValue = param.split(":")
				map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
			}
			displayDebugLog("Map of message: ${descMap}")
			def intEncoding = Integer.parseInt(descMap.encoding, 16)
			if (!oldFirmware && descMap.value != null && intEncoding > 0x18 && intEncoding < 0x3e) {
				displayDebugLog("Data type of message payload is little-endian; reversing byte order")
				// Reverse order of bytes in description's payload for LE data types - required for Hubitat firmware 2.0.5 or newer
				descMap.value = reverseHexString(descMap.value)
				displayDebugLog("Reversed payload value: ${descMap.value}")
			}
			if (descMap.attrId == "0005") {
				displayDebugLog("BETA: Possibly the reset button was short-pressed")
			} else if (descMap.attrId == "FF01" || descMap.attrId == "FF02") {
				displayDebugLog("BETA: Possibly the regular check-in message was received")
				if (lastCheckinEnable) {
					sendEvent(name: "lastCheckinEpoch", value: now())
					sendEvent(name: "lastCheckinTime", value: new Date().toLocaleString())
				}
			} else {
				displayDebugLog("Unable to parse read attribute message")
			}
		}
		if (result.descriptionText)
			displayInfoLog(result.descriptionText)
	}
	if (result) {
		displayDebugLog("Creating event $result")
		return createEvent(result)
	} else
		return [:]
}

// Parse IAS Zone Status message (0 = clear, 1 = detected, or 3 = tested)
private Map parseZoneStatusMessage(status) {
	def value = ["clear", "detected", "tested"]
	def eventType = ["Clear", "Detected", "Tested"]
	def descText = ["All clear", "Smoke detected", "Completed self-test"]
	if (otherCheckinEnable) {
		sendEvent(name: "last${eventType}Epoch", value: now())
		sendEvent(name: "last${eventType}Time", value: new Date().toLocaleString())
	}
	return [
		name: 'smoke',
		value: value[status],
		isStateChange: true,
		descriptionText: descText[status]
	]
}

def resetToClear() {
	sendEvent(name:"smoke", value:"clear")
}

def test() {
	// Sends a command to sensor to complete self-test. An alarm beep indicates normal operation.
	// BETA: Need to confirm attrID as 0xFFF0 (smoke detector uses attrID 0xFFF1)
	return zigbee.writeAttribute(0x0500, 0xFFF0, DataType.UINT32, 0x03010000, [mfgCode: "0x115F"])
}

private def displayDebugLog(message) {
	if (debugLogging)
		log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
	if (infoLogging || state.prefsSetCount != 1)
		log.info "${device.displayName}: ${message}"
}

// installed() runs just after a sensor is paired
def installed() {
	state.prefsSetCount = 0
}

// configure() runs after installed() when a sensor is paired
def configure() {
	displayInfoLog("Configuring")
	init()
	state.prefsSetCount = 1
	return
}

// updated() will run every time user saves preferences
def updated() {
	displayInfoLog("Updating preference settings")
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
	init()
}

def init() {
	if (sensLevel) {
		displayInfoLog("Setting gas sensitivity level to ${sensLevel}")
		// BETA: Need to confirm attrID as 0xFFF0 (smoke detector uses attrID 0xFFF1)
		zigbee.writeAttribute(0x0500, 0xFFF0, DataType.UINT32, sensLevel, [mfgCode: "0x115F"])
		// OR TRY: zigbee.writeAttribute(0x0500, 0xFFF0, DataType.UINT32, Integer.parseInt(sensLevel), [mfgCode: "0x115F"])
	}
}