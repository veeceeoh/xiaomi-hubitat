/**
 *  Xiaomi "Original" Motion Sensor
 *  Device Driver for Hubitat Elevation hub
 *  Version 0.5
 *
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
 *  Based on SmartThings device handler code by a4refillpad
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
 *  Code reworked for use with Hubitat Elevation hub by veeceeoh
 *
 *  Known issues:
 *  + Xiaomi devices send reports based on changes, and a status report every 50-60 minutes. These settings cannot be adjusted.
 *  + The battery level / voltage is not reported at pairing. Wait for the first status report, 50-60 minutes after pairing.
 *  + Pairing Xiaomi devices can be difficult as they were not designed to use with a Hubitat hub.
 *    Holding the sensor's reset button until the LED blinks will start pairing mode.
 *    3 quick flashes indicates success, while one long flash means pairing has not started yet.
 *    In either case, keep the sensor "awake" by short-pressing the reset button repeatedly, until recognized by Hubitat.
 *  + The connection can be dropped without warning. To reconnect, put Hubitat in "Discover Devices" mode, then short-press
 *    the sensor's reset button, and wait for the LED - 3 quick flashes indicates reconnection. Otherwise, short-press again.
 *
 */

metadata {
	definition (name: "Xiaomi Original Motion Sensor", namespace: "veeceeoh", author: "veeceeoh") {
		capability "Motion Sensor"
		capability "Sensor"
		capability "Battery"

		attribute "lastCheckin", "String"
		attribute "lastCheckinDate", "String"
    attribute "lastMotion", "String"
    attribute "lastMotionDate", "String"
		attribute "batteryLastReplaced", "String"

		//fingerprint for Xioami "original" Motion Sensor
		fingerprint profileId: "0104", deviceId: "0104", inClusters: "0000, 0003, FFFF, 0019", outClusters: "0000, 0004, 0003, 0006, 0008, 0005, 0019", manufacturer: "LUMI", model: "lumi.sensor_motion", deviceJoinName: "Xiaomi Motion"

		command "resetBatteryReplacedDate"
    command "resetToMotionInactive"
	}

	preferences {
		//Reset to No Motion Config
		input "motionreset", "number", title: "After motion is detected, wait __ second(s) until resetting to inactive state (default is 60, same as hardware reset).", description: "", range: "1..7200"
		//Date & Time Config
		input name: "dateformat", type: "enum", title: "Date Format for lastCheckin: US (MDY), UK (DMY), or Other (YMD)", description: "", options:["US","UK","Other"]
		input name: "clockformat", type: "bool", title: "Use 24 hour clock?", description: ""
		//Battery Reset Config
    input name: "voltsmin", title: "Min Volts (A battery needs replacing at ___ volts, Range 2.0 to 2.7)", type: "decimal", range: "2..2.7", defaultValue: 2.5
		input name: "voltsmax", title: "Max Volts (A battery is at 100% at ___ volts, range 2.8 to 3.4)", type: "decimal", range: "2.8..3.4", defaultValue: 3
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	def cluster = description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	//log.debug "${device.displayName}: Parsing description: ${description}"

	// Determine current time and date in the user-selected date format and clock style
	def now = formatDate()
	def nowDate = new Date(now).getTime()

	// Any report - temp, humidity, pressure, & battery - results in a lastCheckin event and update to Last Checkin tile
	// However, only a non-parseable report results in lastCheckin being displayed in events log
	sendEvent(name: "lastCheckin", value: now)
	sendEvent(name: "lastCheckinDate", value: nowDate)

	Map map = [:]

	// Send message data to appropriate parsing function based on the type of report
	if (cluster == "0406") {
		map = parseMotion()
		sendEvent(name: "lastMotion", value: now)
		sendEvent(name: "lastMotionDate", value: nowDate)
	} else if (cluster == "0000" & attrId == "0005") {
		log.debug "${device.displayName}: Reset button was short-pressed"
	} else if  (cluster == "0000" & (attrId == "FF01" || attrId == "FF02")) {
		map = parseBattery(valueHex)
	} else {
		log.debug "${device.displayName}: was unable to parse ${description}"
	}

	if (map) {
		log.debug "${map.descriptionText}"
		return createEvent(map)
	} else
		return [:]
}

// Parse motion active report
private parseMotion() {
	def seconds = motionreset ? motionreset : 60
	// The sensor only sends a motion detected message so reset to motion inactive is performed in code
	runIn(seconds, resetToMotionInactive)
	return [
		name: 'motion',
		value: 'active',
		isStateChange: true,
		descriptionText: "${device.displayName}: Detected motion",
	]
}

// Convert raw 4 digit integer voltage value into percentage based on minVolts/maxVolts range
private parseBattery(description) {
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
	def result = [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		isStateChange: true,
		descriptionText: "${device.displayName}: Battery level is ${roundedPct}%, raw battery is ${rawVolts}V"
	]
	return result
}

// If currently in 'active' motion detected state, resetToMotionInactive() resets to 'inactive' state and displays 'no motion'
def resetToMotionInactive() {
	if (device.currentState('motion')?.value == "active") {
		def seconds = motionreset ? motionreset : 60
		def inactiveText = "${device.displayName} reset to motion inactive after ${seconds} seconds"
		sendEvent(
			name:'motion',
			value:'inactive',
			isStateChange: true,
			descriptionText: inactiveText
		)
		log.debug inactiveText
	}
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
	def now = formatDate(true)
	def newlyPaired = paired ? " for newly paired sensor" : ""
	sendEvent(name: "batteryLastReplaced", value: now)
	log.debug "${device.displayName}: Setting Battery Last Replaced to current date${newlyPaired}"
}

// installed() runs just after a sensor is paired
def installed() {
	if (!batteryLastReplaced) resetBatteryReplacedDate(true)
}

// configure() runs after installed() when a sensor is paired
def configure() {
	log.debug "${device.displayName}: Configuring"
	if (!batteryLastReplaced) resetBatteryReplacedDate(true)
	return
}

// updated() will run twice every time user saves preferences
def updated() {
    log.debug "${device.displayName}: Updating preference settings"
        if(battReset){
        resetBatteryReplacedDate()
        device.updateSetting("battReset", false)
    }
}

def formatDate(batteryReset) {
	def correctedTimezone = ""
	def timeString = clockformat ? "HH:mm:ss" : "h:mm:ss aa"

	// If user's hub timezone is not set, display error messages in log and events log, and set timezone to GMT to avoid errors
	if (!(location.timeZone)) {
		correctedTimezone = TimeZone.getTimeZone("GMT")
		log.error "${device.displayName}: Time Zone not set, so GMT was used. Please set up your Hubitat hub location."
		sendEvent(name: "error", value: "", descriptionText: "ERROR: Time Zone not set, so GMT was used. Please set up your Hubitat hub location.")
	}
	else {
		correctedTimezone = location.timeZone
	}

	if (dateformat == "US" || dateformat == "" || dateformat == null) {
		if (batteryReset)
			return new Date().format("MMM dd yyyy", correctedTimezone)
		else
			return new Date().format("EEE MMM dd yyyy ${timeString}", correctedTimezone)
	}
	else if (dateformat == "UK") {
		if (batteryReset)
			return new Date().format("dd MMM yyyy", correctedTimezone)
		else
			return new Date().format("EEE dd MMM yyyy ${timeString}", correctedTimezone)
	}
	else {
		if (batteryReset)
			return new Date().format("yyyy MMM dd", correctedTimezone)
		else
			return new Date().format("EEE yyyy MMM dd ${timeString}", correctedTimezone)
	}
}
