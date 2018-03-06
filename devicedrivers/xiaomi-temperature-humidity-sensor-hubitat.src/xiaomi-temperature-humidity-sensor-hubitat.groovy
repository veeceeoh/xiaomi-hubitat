/**
 * Xiaomi "Original" & Aqara Temperature Humidity Sensor
 * Device Driver for Hubitat Elevation hub
 * Version 0.6
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License.
 *
 * Based on SmartThings device handler code by a4refillpad
 * With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
 * Code reworked for use with Hubitat Elevation hub by veeceeoh
 *
 * Known issues:
 * + Xiaomi devices send reports based on changes, and a status report every 50-60 minutes. These settings cannot be adjusted.
 * + The battery level / voltage is not reported at pairing. Wait for the first status report, 50-60 minutes after pairing.
 * + Pairing Xiaomi devices can be difficult as they were not designed to use with a Hubitat hub.
 *  Holding the sensor's reset button until the LED blinks will start pairing mode.
 *  3 quick flashes indicates success, while one long flash means pairing has not started yet.
 *  In either case, keep the sensor "awake" by short-pressing the reset button repeatedly, until recognized by Hubitat.
 * + The connection can be dropped without warning. To reconnect, put Hubitat in "Discover Devices" mode, then short-press
 *  the sensor's reset button, and wait for the LED - 3 quick flashes indicates reconnection. Otherwise, short-press again.
 *
 */

metadata {
	definition (name: "Xiaomi Temperature Humidity Sensor", namespace: "veeceeoh", author: "veeceeoh") {
		capability "Temperature Measurement"
		capability "Relative Humidity Measurement"
		capability "Sensor"
		capability "Battery"

		attribute "lastCheckin", "String"
		attribute "lastCheckinDate", "String"
		attribute "batteryLastReplaced", "String"

		//fingerprint for Xioami "original" Temperature Humidity Sensor
		fingerprint endpointId: "01", profileId: "0104", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_ht"
		//fingerprint for Xioami Aqara Temperature Humidity Sensor
		fingerprint profileId: "0104", deviceId: "5F01", inClusters: "0000, 0003, FFFF, 0402, 0403, 0405", outClusters: "0000, 0004, FFFF", manufacturer: "LUMI", model: "lumi.weather"

		command "resetBatteryReplacedDate"
	}

	preferences {
		//Temp and Humidity Offsets
		input "tempOffset", "decimal", title:"Temperature Offset", description:"", range:"*..*"
		input "humidOffset", "decimal", title:"Humidity Offset", description:"", range: "*..*"
		input "pressOffset", "decimal", title:"Pressure Offset (Aqara model only)", description:"", range: "*..*"
		input name:"PressureUnits", type:"enum", title:"Pressure Units (Aqara model only)", description:"", options:["mbar", "kPa", "inHg", "mmHg"]
		//Date & Time Config
		input name: "dateformat", type: "enum", title: "Date Format for lastCheckin: US (MDY), UK (DMY), or Other (YMD)", description: "", options:["US","UK","Other"]
		input name: "clockformat", type: "bool", title: "Use 24 hour clock?", description: ""
		//Battery Voltage Offset
		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.7)", type: "decimal", range: "2..2.7", defaultValue: 2.5
		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.8 to 3.4)", type: "decimal", range: "2.8..3.4", defaultValue: 3
		//Debug logging Config
		input name: "debugLogging", type: "bool", title: "Display debug log messages", description: "", defaultValue: false
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	def cluster = description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	displayDebugLog("Parsing description: ${description}")

	// Determine current time and date in the user-selected date format and clock style
	def now = formatDate()
	def nowDate = new Date(now).getTime()

	// Any report - temp, humidity, pressure, & battery - results in a lastCheckin event and update to Last Checkin tile
	// However, only a non-parseable report results in lastCheckin being displayed in events log
	sendEvent(name: "lastCheckin", value: now, displayed: false)
	sendEvent(name: "lastCheckinDate", value: nowDate, displayed: false)

	Map map = [:]

	// Send message data to appropriate parsing function based on the type of report
	if (cluster == "0402") {
		map = parseTemperature(valueHex)
	} else if (cluster == "0405") {
		map = parseHumidity(valueHex)
	} else if (cluster == "0403") {
		map = parsePressure(valueHex)
	} else if (cluster == "0000" & attrId == "0005") {
		displayDebugLog("Reset button was short-pressed")
	} else if	(cluster == "0000" & (attrId == "FF01" || attrId == "FF02")) {
		map = parseBattery(valueHex)
	} else {
		displayDebugLog("Unable to parse ${description}")
	}

	if (map) {
		displayDebugLog(map.descriptionText)
		return createEvent(map)
	} else
		return [:]
}

// Calculate temperature with 0.1 precision in C or F unit as set by hub location settings
private parseTemperature(description) {
	float temp = Integer.parseInt(description,16)/100
	displayDebugLog("Raw reported temperature = ${temp}°C")
	def offset = tempOffset ? tempOffset : 0
	temp = (temp > 100) ? (temp - 655.35) : temp
	temp = (temperatureScale == "F") ? ((temp * 1.8) + 32) + offset : temp + offset
	temp = temp.round(1)
	return [
		name: 'temperature',
		value: temp,
		unit: "${temperatureScale}",
		isStateChange: true,
		descriptionText: "Temperature is ${temp}°${temperatureScale}",
		translatable:true
	]
}

// Calculate humidity with 0.1 precision
private parseHumidity(description) {
	float humidity = Integer.parseInt(description,16)/100
	displayDebugLog("Raw reported humidity = ${humidity}%")
	humidity = humidityOffset ? (humidity + humidityOffset) : humidity
	humidity = humidity.round(1)
	return [
		name: 'humidity',
		value: humidity,
		unit: "%",
		isStateChange: true,
		descriptionText: "Humidity is ${humidity}%",
	]
}

// Parse pressure report
private parsePressure(description) {
	float pressureval = Integer.parseInt(description[0..3], 16)
	if (!(PressureUnits)) {
		PressureUnits = "mbar"
	}
	displayDebugLog("Converting ${pressureval} to ${PressureUnits}")
	switch (PressureUnits) {
		case "mbar":
			pressureval = (pressureval/10) as Float
			pressureval = pressureval.round(1);
			break;
		case "kPa":
			pressureval = (pressureval/100) as Float
			pressureval = pressureval.round(2);
			break;
		case "inHg":
			pressureval = (((pressureval/10) as Float) * 0.0295300)
			pressureval = pressureval.round(2);
			break;
		case "mmHg":
			pressureval = (((pressureval/10) as Float) * 0.750062)
			pressureval = pressureval.round(2);
			break;
	}
	pressureval = pressOffset ? (pressureval + pressOffset) : pressureval
	pressureval = pressureval.round(2);

	return [
		name: 'pressure',
		value: pressureval,
		unit: PressureUnits,
		isStateChange: true,
		descriptionText: "Pressure is ${pressureval} ${PressureUnits}"
	]
}

// Convert raw 4 digit integer voltage value into percentage based on minVolts/maxVolts range
private parseBattery(description) {
	displayDebugLog("Battery parse string = ${description[6..9]}")
	def rawValue = Integer.parseInt((description[8..9] + description[6..7]),16)
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
		descriptionText: "Battery level is ${roundedPct}%, raw battery is ${rawVolts}V"
	]
	return result
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
	def now = formatDate(true)
	def logText = "Setting Battery Last Replaced to current date"
	sendEvent(name: "batteryLastReplaced", value: now)
	if (paired)
		log.debug "${logText} for newly paired sensor"
	displayDebugLog(logText)
}

private def displayDebugLog(message) {
	if (debugLogging) log.debug "${device.displayName}: ${message}"
}

// installed() runs just after a sensor is paired
def installed() {
	displayDebugLog("Installing")
	if (!batteryLastReplaced) resetBatteryReplacedDate(true)
}

// configure() runs after installed() when a sensor is paired
def configure() {
	displayDebugLog("Configuring")
	return
}

// updated() will run every time user saves preferences
def updated() {
	displayDebugLog("Updating preference settings")
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
