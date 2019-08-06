/**
 *
 *  IMPORT URL: https://raw.githubusercontent.com/veeceeoh/xiaomi-hubitat/master/devicedrivers/xiaomi-honeywell-gas-detector.src/xiaomi-honeywell-gas-detector.groovy
 *
 *  Xiaomi MiJia Honeywell Gas Detector - model JTQJ-BF-01LM/BW
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.5b BETA
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

import hubitat.zigbee.clusters.iaszone.ZoneStatus

metadata {
	definition (name: "Xiaomi Gas Detector", namespace: "veeceeoh", author: "veeceeoh", importUrl: "https://raw.githubusercontent.com/veeceeoh/xiaomi-hubitat/master/devicedrivers/xiaomi-honeywell-gas-detector.src/xiaomi-honeywell-gas-detector.groovy") {
		capability "Configuration"
		capability "Sensor"
		capability "Smoke Detector"
		capability "TestCapability"
		// This driver uses the Smoke Detector capabilty because "Gas Detector" is not available
		// attributes: smoke ("detected","clear","tested")

		command "checkSensitivityLevel"
		command "sendClearEvent"
		command "sendDetectedEvent"
		command "setSensitivityLevel", [[name: "Choose gas sensitivity level", type: "ENUM", description: "Choose a level", constraints: ["high","medium","low"]]]
		command "testConnection"
		//command "parse", [[name: "Zigbee Message", type: "STRING", description: "Parse string to test", constraints: ["STRING"]]]

		attribute "lastCheckinTime", "String"
		attribute "lastCheckinEpoch", "String"
		attribute "lastClearTime", "String"
		attribute "lastClearEpoch", "String"
		attribute "lastDetectedTime", "String"
		attribute "lastDetectedEpoch", "String"
		attribute "lastTestedTime", "String"
		attribute "lastTestedEpoch", "String"
		attribute "sensitivityLevel", "String"

		fingerprint endpointId: "01", profileID: "0104", deviceID: "0101", inClusters: "0000,0004,0003,0001,0002,000A,0500", outClusters: "0019,000A", manufacturer: "LUMI", model: "lumi.sensor_natgas"
	}

	preferences {
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
	displayDebugLog("Parsing message: $description")
	def result = []
	if (description?.startsWith('enroll request')) {
		List cmds = zigbee.enrollResponse(1200)
		displayDebugLog("Zigbee IAS Enroll response: $cmds")
		result = cmds?.collect {new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE)}
	} else if (description?.startsWith('zone status')) {
		// Parse gas - clear / detected / tested status report
		result = parseZoneStatusMessage(Integer.parseInt(description[17]))
	} else if (description?.startsWith('cat')) {
		Map descMap = zigbee.parseDescriptionAsMap(description)
		// Ignore "heartbeat" catchall messages that occur every minute
		if (descMap.clusterId != "000A") {
			displayDebugLog("Zigbee parse map of catchall = $descMap")
			if (descMap.clusterId == "0500") {
				if (descMap.data.get(0) == "00")
					displayDebugLog("Check/Change sensitivity command was successfully received")
				else if (description.endsWith('F0FF'))
					displayDebugLog("BETA: Received unknown Attribute ID 0xFFF0 value = ${Integer.parseInt(descMap.data.get(0),16)}")
				else
					displayDebugLog("Unknown catchall message")
			}
		}
	} else if (description?.startsWith('re')) {
		description = description - "read attr - "
		Map descMap = (description).split(",").inject([:]) {
			map, param ->
			def nameAndValue = param.split(":")
			map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
		}
		def intEncoding = Integer.parseInt(descMap.encoding, 16)
		if (!oldFirmware && descMap.value != null && intEncoding > 0x18 && intEncoding < 0x3e) {
			displayDebugLog("Data type of message payload is little-endian; reversing byte order")
			// Reverse order of bytes in description's payload for LE data types - required for Hubitat firmware 2.0.5 or newer
			descMap.value = reverseHexString(descMap.value)
			displayDebugLog("Reversed payload value: ${descMap.value}")
		}
		if (descMap.attrId == "FFF0" & descMap.value?.startsWith('020')) {
			parseSensitivityLevel(descMap.value[3] as Integer)
		} else if (descMap.attrId == "FF01") {
			parseCheckinMessage(descMap.value)
			if (lastCheckinEnable) {
				sendEvent(name: "lastCheckinEpoch", value: now())
				sendEvent(name: "lastCheckinTime", value: new Date().toLocaleString())
			}
		} else {
			displayDebugLog("Unknown read attribute message")
		}
	}
	if (result.descriptionText) {
		displayInfoLog(result.descriptionText)
		displayDebugLog("Creating event $result")
		sendEvent(result)
	} else
		return result
}

// Parse IAS Zone Status message (0 = clear, 1 = detected, or 3 = tested)
private Map parseZoneStatusMessage(status) {
	def value = ["clear", "detected", "tested"]
	def eventType = ["Clear", "Detected", "Tested"]
	def descText = ["All clear", "Gas detected", "Self-test button pushed"]
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

// Reverses order of bytes in hex string
def reverseHexString(hexString) {
	def reversed = ""
	for (int i = hexString.length(); i > 0; i -= 2) {
		reversed += hexString.substring(i - 2, i )
	}
	return reversed
}

def parseCheckinMessage(hexString) {
	// Format example of checkin message payload
	// L  Temp        RSSI dB          ?          ? Gas Dens              ?
	// -- -------- ---------- ---------- ---------- -------- --------------
	// 1B 03 28 26 05 21 0B00 08 21 0821 09 21 0004 64 20 00 96 23 00000000
	//        38°C         11       2108          4        0              0
	//        INT8     UINT16     UINT16     UINT16    UINT8         UINT32
	def tempCelcius = hexStrToSignedInt(hexString[6..7])
	def tempLocal = convertTemperatureIfNeeded(tempCelcius.toFloat(),"c",0)
	def rssi = hexStrToUnsignedInt(hexString[14..15] + hexString[12..13])
	def gasDensity = hexStrToUnsignedInt(hexString[36..37])
	displayInfoLog("(BETA) Check-in report: RSSI dB = $rssi, Internal Temp = ${tempLocal}°${location.temperatureScale}, Gas Density = $gasDensity")
}

def parseSensitivityLevel(value) {
	if (value >= 1 & value <= 3) {
		def levelText = [1:"high", 2:"medium", 3:"low"]
		displayInfoLog("Sensitivity level is set to ${levelText[value]}")
		sendEvent(name: "sensitivityLevel", value: levelText[value])
	} else
		displayDebugLog("Invalid sensitivity level message value received")
}

def sendClearEvent() {
	manualEvent(0)
}

def sendDetectedEvent() {
	manualEvent(1)
}

def manualEvent(value) {
	def result = parseZoneStatusMessage(value)
	result.descriptionText += " (manually created event)"
	displayInfoLog(result.descriptionText)
	displayDebugLog("Manually creating event $result")
	sendEvent(result)
}

def testConnection() {
	// Sends a command to sensor to complete self-test. An alarm beep indicates normal operation.
	def cmds = [
		"he wattr 0x${device.deviceNetworkId} 0x01 0x0500 0xFFF1 0x23 {03010000} {115F}", "delay 200"
	]
	// cmds = zigbee.writeAttribute(0x0500, 0xFFF1, 0x23, 0x03010000, [mfgCode: "0x115F"])
	// Data type 0x23 = DataType.UINT32
	displayInfoLog("Sending request to perform self-test")
	displayDebugLog("Sending commands: $cmds")
	return cmds
}

def checkSensitivityLevel() {
	// Sends a request to sensor to return the currently set sensitivity level.
	displayDebugLog("Sending command to check sensitivity level")
	return zigbee.readAttribute(0x0500, 0xFFF0, [mfgCode: "0x115F"])
}

def setSensitivityLevel(level) {
	def levelText = [1:"high", 2:"medium", 3:"low"]
	def attrValue = [1:"02010000", 2:"02020000", 3:"02030000"]
	def levelInt
	for (int i = 1; i < 4; i++) {
		levelInt = i
		if (levelText[i] == level)
			break
	}
	// sensitivity level attribute payload to send - low = 0x02030000, medium = 0x02020000, high = 0x02010000
	// def cmds = [zigbee.writeAttribute(0x0500, 0xFFF1, 0x23, attrValue[Integer.parseInt(sensLevel)], [mfgCode: "0x115F"]), "delay 200"]
	// Data type 0x23 = DataType.UINT32
	def cmds = [
		"he wattr 0x${device.deviceNetworkId} 0x01 0x0500 0xFFF1 0x23 {${attrValue[levelInt]}} {115F}", "delay 200"
	]
	displayInfoLog("Requesting sensitivity level change to $level")
	displayDebugLog("Sending commands: $cmds")
	runInMillis(500, checkSensitivityLevel)
	return cmds
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
	runIn(1800, turnOffInfoLogging)
}

// configure() runs after installed() when a sensor is paired
def configure() {
	displayInfoLog("Configuring")
	if (!device.currentValue('sensitivityLevel'))
		return checkSensitivityLevel()
}

// updated() will run every time user saves preferences
def updated() {
	displayInfoLog("Updating preference settings")
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
}

def turnOffInfoLogging() {
	displayInfoLog("Info logging is being automatically disabled 30 minutes after pairing")
	state.prefsSetCount = 1
}
