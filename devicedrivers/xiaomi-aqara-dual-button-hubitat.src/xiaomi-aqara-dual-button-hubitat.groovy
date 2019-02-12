/*
 *  Xiaomi Aqara Smart Light Switch - Wireless 2 button model WXKG02LM
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.6.5 gn0st1c
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
 *
 *
 *  https://xiaomi-mi.com/sockets-and-sensors/xiaomi-aqara-smart-light-control-set
 */

metadata {
	definition (name: "Xiaomi Aqara Dual Button Light Switch", namespace: "gn0st1c", author: "gn0st1c") {
		capability "PushableButton"
		capability "Battery"
		capability "Sensor"

		attribute "lastCheckin", "String"
		attribute "batteryLastReplaced", "String"
		attribute "buttonPressed", "String"

		fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_86sw2Un"

		command "resetBatteryReplacedDate"
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
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	def endpoint = description.split(",").find {it.split(":")[0].trim() == "endpoint"}?.split(":")[1].trim()
	def cluster	= description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	Map map = [:]

	if (!oldFirmware & valueHex != null)
		// Reverse order of bytes in description's value hex string - required for Hubitat firmware 2.0.5 or newer
		valueHex = reverseHexString(valueHex)

	displayDebugLog("Parsing message: ${description}")
	displayDebugLog("Message payload: ${valueHex}")

	// lastCheckin can be used with webCoRE
	sendEvent(name: "lastCheckin", value: now())

	// Send message data to appropriate parsing function based on the type of report
	if (cluster == "0006") {
		// Parse button press: endpoint 01 = left, 02 = right, 03 = both
		map = parseButtonPress(Integer.parseInt(endpoint))
	} else if (cluster == "0000" & attrId == "0005") {
		displayDebugLog "Reset button was short-pressed"
		// Parse battery level from longer type of announcement message
		map = (valueHex.size() > 60) ? parseBattery(valueHex.split('FF42')[1]) : [:]
	} else if (cluster == "0000" & (attrId == "FF01" || attrId == "FF02")) {
		// Parse battery level from hourly announcement message
		map = (valueHex.size() > 30) ? parseBattery(valueHex) : [:]
	} else if (!(cluster == "0000" & attrId == "0001")) {
		displayDebugLog "Unable to parse ${description}"
	}

	if (map != [:]) {
		displayDebugLog("Creating event $map")
		return createEvent(map)
	} else
		return [:]
}

// Reverses order of bytes in hex string
def reverseHexString(hexString) {
	def reversed = ""
	for (int i = hexString.length(); i > 0; i -= 2) {
		reversed += hexString.substring(i - 2, i )
	}
	return reversed
}

// Build event map based on type of button press
private Map parseButtonPress(value) {
	def pushType = ["", "Left", "Right", "Both"]
	def descText = "${pushType[value]} button${(value == 3) ? "s" : ""} pressed (Button $value pushed)"
	displayInfoLog(descText)
	displayDebugLog("Setting buttonPressed to current date/time for webCoRE")
	sendEvent(name: "buttonPressed", value: now(), descriptionText: "Updated buttonPressed (webCoRE)")
	return [
		name: 'pushed',
		value: value,
		isStateChange: true,
		descriptionText: descText
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
	displayInfoLog(descText)
	def result = [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		isStateChange: true,
		descriptionText: descText
	]
	return result
}

private def displayDebugLog(message) {
	if (debugLogging) log.debug "${device.displayName}: ${message}"
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

// this call is here to avoid Groovy errors when the Push command is used
// it is empty because the Xioami button is non-controllable
def push() {
	displayDebugLog("No action taken on Push Command. This button cannot be controlled.")
}

// installed() runs just after a sensor is paired
def installed() {
	state.prefsSetCount = 0
	displayInfoLog("Installing")
}

// configure() runs after installed() when a sensor is paired or reconnected
def configure() {
	displayInfoLog("Configuring")
	init()
	displayInfoLog("Number of buttons = 3")
	state.prefsSetCount = 1
	return
}

// updated() runs every time user saves preferences
def updated() {
	displayInfoLog("Updating preference settings")
	init()
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
}

def init() {
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
	sendEvent(name: "numberOfButtons", value: 3)
}
