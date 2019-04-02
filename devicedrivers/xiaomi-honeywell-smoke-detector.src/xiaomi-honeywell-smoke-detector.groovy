/**
 *  Xiaomi MiJia Honeywell Smoke Detector - model JTYJ-GD-01LM/BW
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.5.1
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
 *  Review of device ... https://blog.tlpa.nl/2017/11/12/xiaomi-also-mijia-and-honeywell-smart-fire-detector/
 *  Device purchased here (€20.54)... https://www.gearbest.com/alarm-systems/pp_615081.html
 *  RaspBee packet sniffer... https://github.com/dresden-elektronik/deconz-rest-plugin/issues/152
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
		capability "TestCapability"
		
		// attributes: smoke ("detected","clear","tested")

		command "resetBatteryReplacedDate"
		command "resetToClear"
		command "test"

		attribute "batteryLastReplaced", "String"
		attribute "lastCheckin", "String"
		attribute "lastClear", "String"
		attribute "lastDetected", "String"
		attribute "lastTested", "String"

		fingerprint endpointId: "01", profileID: "0104", deviceID: "0402", inClusters: "0000,0003,0012,0500,000C,0001", outClusters: "0019", manufacturer: "LUMI", model: "lumi.sensor_smoke"
	}

	preferences {
		//Battery Voltage Range
 		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.7). Default = 2.5 Volts", description: "", type: "decimal", range: "2..2.7"
 		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.8 to 3.4). Default = 3.0 Volts", description: "", type: "decimal", range: "2.8..3.4"
 		//Logging Message Config
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
		//Firmware 2.0.5 Compatibility Fix Config
		input name: "oldFirmware", type: "bool", title: "DISABLE 2.0.5 firmware compatibility fix (for users of 2.0.4 or earlier)", description: ""
		input name: "sensitivity", type: "enum", title: "Smoke sensitivity", description: "", options: [[0x04010000:"Smoke free area"],[0x04020000:"Slight amount of smoke"],[0x04030000:"Medium amount of smoke"]], defaultValue: 0x04010000, required: true
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	displayDebugLog("Parsing message: ${description}")

    if (description.startsWith("catchall"))
    	return

	def status = (description?.startsWith('zone status')) ? description[17] : ""
	def attrId = status ? "" : description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def encoding = Integer.parseInt(description.split(",").find {it.split(":")[0].trim() == "encoding"}?.split(":")[1].trim(), 16)
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	Map map = [:]

	if (!oldFirmware & valueHex != null & encoding > 0x18 & encoding < 0x3e) {
		displayDebugLog("Data type of payload is little-endian; reversing byte order")
		// Reverse order of bytes in description's payload for LE data types - required for Hubitat firmware 2.0.5 or newer
		valueHex = reverseHexString(valueHex)
	}

	displayDebugLog("Message payload: ${valueHex}")

	// lastCheckin can be used with webCoRE
	sendEvent(name: "lastCheckin", value: now())

	if (status) {
		// Parse smoke - clear / detected / tested status report
		map = parseZoneStatusMessage(Integer.parseInt(status))
	} else if (description?.startsWith('enroll request')) {
		List cmds = zigbee.enrollResponse()
		log.debug "enroll response: ${cmds}"
		result = cmds?.collect {new hubitat.device.HubAction(it)}
	} else if (attrId == "0005") {
		displayDebugLog("Reset button was short-pressed")
		// Parse battery level from longer type of announcement message
		map = (valueHex.size() > 60) ? parseBattery(valueHex.split('FF42')[1]) : [:]
	} else if (attrId == "FF01" || attrId == "FF02") {
		// Parse battery level from hourly announcement message
		map = parseBattery(valueHex)
	} else {
		displayDebugLog("Unable to parse message")
	}

	if (map != [:]) {
		displayInfoLog(map.descriptionText)
		displayDebugLog("Creating event $map")
		return createEvent(map)
	} else
		return [:]
}

def test() { // A beep indicates normal operation
	return zigbee.writeAttribute(0x0500, 0xFFF1, DataType.UINT32, 0x03010000, [mfgCode: "0x115F"])
}

// Reverses order of bytes in hex string
def reverseHexString(hexString) {
	def reversed = ""
	for (int i = hexString.length(); i > 0; i -= 2) {
		reversed += hexString.substring(i - 2, i )
	}
	return reversed
}

// Parse IAS Zone Status message (0 = clear, 1 = detected, or 3 = tested)
private Map parseZoneStatusMessage(status) {
	def value = ["clear", "detected", "tested"]
	def coreType = ["Clear", "Detected", "Tested"]
	def descText = ["All clear", "Smoke detected", "Completed self-test"]
	sendEvent(name: "last$coreType", value: now())
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
	def result = [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		isStateChange: true,
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
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
	init()
}

def init() {
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
	
	displayInfoLog("Setting sensitivity to ${sensitivity}")
	zigbee.writeAttribute(0x0500, 0xFFF1, DataType.UINT32, Integer.parseInt(sensitivity), [mfgCode: "0x115F"])
}
