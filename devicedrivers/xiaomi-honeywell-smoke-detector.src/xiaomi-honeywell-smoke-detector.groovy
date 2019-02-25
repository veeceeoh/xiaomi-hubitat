/**
 *  Xiaomi MiJia Honeywell Smoke Detector model JTYJ-GD-01LM/BW
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.6b
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
 *  Review of device... https://blog.tlpa.nl/2017/11/12/xiaomi-also-mijia-and-honeywell-smart-fire-detector/
 *  Device purchase link... https://www.gearbest.com/alarm-systems/pp_615081.html
 *  Instructions in English... http://files.xiaomi-mi.com/files/MiJia_Honeywell/MiJia_Honeywell_Smoke_Detector_EN.pdf
 *  Fire Certification is CCCF... https://www.china-certification.com/en/ccc-certification-for-fire-safety-products-cccf/
 *  Note: in order to be covered by your insurance and for peace of mind, please also use correctly certified detectors if CCCF is not accepted in your country
 *
 *  Battery: The device is powered by a lithium CR123a cell (expected life of 5 years)
 *
 *  Known issues:
 *  + Xiaomi devices send reports based on changes, and a status report every 50-60 minutes. These settings cannot be adjusted.
 *  + The battery level / voltage is not reported at pairing. Wait for the first status report, 50-60 minutes after pairing.
 *  + Pairing Xiaomi devices can be difficult as they were not designed to use with a Hubitat hub.
 *    To put in pairing mode, press main button 3 times
 *  + The connection can be dropped without warning. To reconnect, put Hubitat in "Discover Devices" mode, and follow the pairing procedure.
 *
 */

metadata {
	definition (name: "Xiaomi Smoke Detector", namespace: "veeceeoh", author: "veeceeoh") {
		capability "Battery"
		capability "Configuration"
		capability "Sensor"
		capability "Smoke Detector"
		// attributes: smoke ("detected","clear","tested")

		command "resetBatteryReplacedDate"
		command "resetToClear"

		attribute "batteryLastReplaced", "String"
		attribute "lastCheckinTime", "String"
		attribute "lastCheckinEpoch", "String"
		attribute "lastClearTime", "String"
		attribute "lastClearEpoch", "String"
		attribute "lastDetectedTime", "String"
		attribute "lastDetectedEpoch", "String"
		attribute "lastTestedTime", "String"
		attribute "lastTestedEpoch", "String"

		fingerprint endpointId: "01", profileID: "0104", deviceID: "0402", inClusters: "0000,0003,0012,0500,000C,0001", outClusters: "0019", manufacturer: "LUMI", model: "lumi.sensor_smoke"
	}

	preferences {
		//Battery Voltage Range
		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.7). Default = 2.5 Volts", description: "", type: "decimal", range: "2..2.7"
		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.8 to 3.4). Default = 3.0 Volts", description: "", type: "decimal", range: "2.8..3.4"
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
				displayDebugLog("Reset button was short-pressed")
				// Parse battery level from longer type of announcement message
				result = (descMap.value.size() > 60) ? parseBattery(descMap.value.split('FF42')[1]) : [:]
			} else if (descMap.attrId == "FF01" || descMap.attrId == "FF02") {
				// Parse battery level from hourly announcement message
				result = parseBattery(descMap.value)
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

// Convert raw 4 digit integer voltage value into percentage based on minVolts/maxVolts range
private parseBattery(description) {
	displayDebugLog("Battery parse string = ${description}")
	def MsgLength = description.size()
	def rawValue
	for (int i = 4; i < (MsgLength-3); i+=2) {
		if (description[i..(i+1)] == "21") { // Search for byte preceeding battery voltage bytes
			rawValue = Integer.parseInt((description[(i+4)..(i+5)] + description[(i+2)..(i+3)]),16)
			break
		}
	}
	def rawVolts = rawValue / 1000
	def minVolts = voltsmin ? voltsmin : 2.5
	def maxVolts = voltsmax ? voltsmax : 3.0
	def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
	def roundedPct = Math.min(100, Math.round(pct * 100))
	def descText = "Battery level is ${roundedPct}% (${rawVolts} Volts)"
	if (lastCheckinEnable) {
		sendEvent(name: "lastCheckinEpoch", value: now())
		sendEvent(name: "lastCheckinTime", value: new Date().toLocaleString())
	}
	def result = [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		descriptionText: descText
	]
	return result
}

def resetToClear() {
	sendEvent(name:"smoke", value:"clear")
}

private def displayDebugLog(message) {
	if (debugLogging)
		log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
	if (infoLogging || state.prefsSetCount != 1)
		log.info "${device.displayName}: ${message}"
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
	def newlyPaired = paired ? " for newly paired sensor" : ""
	sendEvent(name: "batteryLastReplaced", value: new Date())
	displayInfoLog("Setting Battery Last Replaced to current date${newlyPaired}")
}

// installed() runs just after a sensor is paired
def installed() {
	state.prefsSetCount = 0
	displayDebugLog("Installing")
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
	init()
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
}

def init() {
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
}
