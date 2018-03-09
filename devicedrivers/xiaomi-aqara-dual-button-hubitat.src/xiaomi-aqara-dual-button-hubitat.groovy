/*
 *  Xiaomi Aqara Smart Light Switch - Wireless 2 button model WXKG02LM
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.5 gn0st1c
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
 * https://xiaomi-mi.com/sockets-and-sensors/xiaomi-aqara-smart-light-control-set
 */

metadata {
	definition (name: "Xiaomi Aqara Dual Button Light Switch", namespace: "gn0st1c", author: "gn0st1c") {
		capability "PushableButton"
		capability "Battery"
		capability "Sensor"

		attribute "lastCheckin", "String"
		attribute "lastCheckinDate", "String"
		attribute "lastPressed", "String"
		attribute "lastPressedDate", "String"
		attribute "batteryLastReplaced", "String"

		fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_86sw2Un"
//		fingerprint endpointId: "01", profileId: "0104", deviceId: "5F01", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_86sw2Un"

		command "resetBatteryReplacedDate"
	}

	preferences {
		//Date & Time Config
		input name: "dateformat", type: "enum", title: "Date Format for lastCheckin: US (MDY), UK (DMY), or Other (YMD)", description: "", options:["US","UK","Other"]
		input name: "clockformat", type: "bool", title: "Use 24 hour clock", description: "", defaultValue: true
		//Battery Reset Config
		input name: "voltsmin", title: "Min Volts (A battery needs replacing at ___ volts, Range 2.0 to 2.7)", type: "decimal", range: "2..2.7", defaultValue: 2.5
		input name: "voltsmax", title: "Max Volts (A battery is at 100% at ___ volts, Range 2.8 to 3.4)", type: "decimal", range: "2.8..3.4", defaultValue: 3.2
		//Logging
		input name: "debugLogging", type: "bool", title: "Display debug log messages", description: "", defaultValue: true
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	displayDebugLog "Parsing description: ${description}"
	def endpoint = Integer.parseInt(description.split(",").find {it.split(":")[0].trim() == "endpoint"}?.split(":")[1].trim())
	def cluster = description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	def pressType = ["", "Left button", "Right button", "Both buttons"] 

	// Determine current time and date in the user-selected date format and clock style
	def now = formatDate()
	def nowDate = new Date(now).getTime()

	// lastCheckin and lastPressedDate can be used to determine if the sensor is "awake" and connected
	sendEvent(name: "lastCheckin", value: now)
	sendEvent(name: "lastCheckinDate", value: nowDate)

	Map map = [:]

	// Handle message data based on the type of report
	if (cluster == "0006") {
		// Endpoint used for button number: 01 = Left, 02 = Right, 03 = Both
		map = [
			name: 'pushed',
			value: buttonNumber,
			isStateChange: true,
			descriptionText: "${pressType[endpoint]} pushed"
		]
		sendEvent(name: "lastPressed", value: now)
		sendEvent(name: "lastPressedDate", value: nowDate)
	} else if (cluster == "0000" & attrId == "0005") {
		map = (valueHex.size() > 60) ? parseBattery(valueHex.split('FF42')[1]) : [:]
	} else if (cluster == "0000" & (attrId == "FF01" || attrId == "FF02")) {
		map = (valueHex.size() > 30) ? parseBattery(valueHex) : [:]
	} else if (!(cluster == "0000" & attrId == "0001")) {
		displayDebugLog "Unable to parse ${description}"
	}

	if (map) {
		displayDebugLog "${map.descriptionText}"
		return createEvent(map)
	} else {
		return [:]
	}
}

// Convert raw 4 digit integer voltage value into percentage based on minVolts/maxVolts range
private parseBattery(description) {
	displayDebugLog "Parsing battery: ${description}"
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
	def maxVolts = voltsmax ? voltsmax : 3.2
	def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
	def roundedPct = Math.min(100, Math.round(pct * 100))
	def result = [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		isStateChange: true,
		descriptionText: "${device.displayName}: Battery level is ${roundedPct}%, raw battery is ${rawVolts}V"
	]
	return result
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
	def now = formatDate(true)
	def newlyPaired = paired ? " for newly paired sensor" : ""
	sendEvent(name: "batteryLastReplaced", value: now)
	displayDebugLog "Setting Battery Last Replaced to current date${newlyPaired}"
}

private def displayDebugLog(message) {
	if (debugLogging) log.debug "${device.displayName}: ${message}"
}

def init() {
	sendEvent(name: "numberOfButtons", value: 3)
	if (!batteryLastReplaced)
		resetBatteryReplacedDate(true)
}

// installed() runs just after a sensor is paired
def installed() {
	log.debug "${device.displayName}: installed"
	init()
}

// configure() runs after installed() when a sensor is paired or reconnected
def configure() {
	log.debug "${device.displayName}: configure"
	init()

	return
}

// updated() runs every time user saves preferences
def updated() {
	displayDebugLog "updated"
	init()
}

// this call is here to avoid Groovy errors when the Push command is used
// it is empty because the Xioami button is non-controllable
def push() {
	displayDebugLog "push"
}

def formatDate(batteryReset) {
	def correctedTimezone = ""
	def timeString = clockformat ? "HH:mm:ss" : "h:mm:ss aa"

	// If user's hub timezone is not set, display error messages in log and events log, and set timezone to GMT to avoid errors
	if (!(location.timeZone)) {
		correctedTimezone = TimeZone.getTimeZone("GMT")
		displayDebugLog "Time Zone not set, so GMT was used. Please set up your Hubitat hub location."
		sendEvent(name: "error", value: "", descriptionText: "ERROR: Time Zone not set, so GMT was used. Please set up your Hubitat hub location.")
	} else {
		correctedTimezone = location.timeZone
	}

	if (dateformat == "US" || dateformat == "" || dateformat == null) {
		if (batteryReset)
			return new Date().format("MMM dd yyyy", correctedTimezone)
		else
			return new Date().format("EEE MMM dd yyyy ${timeString}", correctedTimezone)
	} else if (dateformat == "UK") {
		if (batteryReset)
			return new Date().format("dd MMM yyyy", correctedTimezone)
		else
			return new Date().format("EEE dd MMM yyyy ${timeString}", correctedTimezone)
	} else {
		if (batteryReset)
			return new Date().format("yyyy MMM dd", correctedTimezone)
		else
			return new Date().format("EEE yyyy MMM dd ${timeString}", correctedTimezone)
	}
}
