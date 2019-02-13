/**
 * Xiaomi "Original" Temperature Humidity Sensor - model RTCGQ01LM
 * & Aqara Temperature Humidity Sensor - model WSDCGQ11LM
 * Device Driver for Hubitat Elevation hub
 * Version 0.8.1
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
 * With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, mike.maxwell, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
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

		attribute "pressure", "Decimal"
		attribute "lastCheckinEpoch", "String"
		attribute "lastCheckinTime", "Date"
		attribute "batteryLastReplaced", "String"

		//fingerprint for Xioami "original" Temperature Humidity Sensor
		fingerprint profileId: "0104", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_ht"
		//fingerprint for Xioami Aqara Temperature Humidity Sensor
		fingerprint profileId: "0104", inClusters: "0000, 0003, FFFF, 0402, 0403, 0405", outClusters: "0000, 0004, FFFF", manufacturer: "LUMI", model: "lumi.weather"

		command "resetBatteryReplacedDate"
	}

	preferences {
		//Temp and Humidity Offsets
		input "tempOffset", "decimal", title:"Temperature Offset", description:"", range:"*..*"
		input "humidOffset", "decimal", title:"Humidity Offset", description:"", range: "*..*"
		input "pressOffset", "decimal", title:"Pressure Offset (Aqara model only)", description:"", range: "*..*"
		input name:"PressureUnits", type:"enum", title:"Pressure Units (Aqara model only)", description:"", options:["mbar", "kPa", "inHg", "mmHg"]
		//Battery Voltage Range
		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.9). Default = 2.9 Volts", description: "", type: "decimal", range: "2..2.9"
		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.95 to 3.4). Default = 3.05 Volts", description: "", type: "decimal", range: "2.95..3.4"
		//Date/Time Stamp Events Config
		input name: "lastCheckinEnable", type: "bool", title: "Enable custom date/time stamp events for lastCheckin", description: ""
		//Logging Message Config
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
		//Firmware 2.0.5 Compatibility Fix Config
		input name: "oldFirmware", type: "bool", title: "DISABLE 2.0.5 firmware compatibility fix (for users of 2.0.4 or earlier)", description: ""
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	def cluster = description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def encoding = Integer.parseInt(description.split(",").find {it.split(":")[0].trim() == "encoding"}?.split(":")[1].trim(), 16)
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	Map map = [:]

	if (!oldFirmware & valueHex != null & encoding > 0x18 & encoding < 0x3e) {
		displayDebugLog("Data type of payload is little-endian; reversing byte order")
		// Reverse order of bytes in description's payload for LE data types - required for Hubitat firmware 2.0.5 or newer
		valueHex = reverseHexString(valueHex)
	}

	displayDebugLog("Parsing message: ${description}")
	displayDebugLog("Message payload: ${valueHex}")

	// Send message data to appropriate parsing function based on the type of report
	if (cluster == "0402")
		map = parseTemperature(valueHex)
	else if (cluster == "0405")
		map = parseHumidity(valueHex)
	else if (cluster == "0403")
		map = parsePressure(valueHex)
	else if (cluster == "0000" & attrId == "0005")
		displayDebugLog("Reset button was short-pressed")
	else if	(cluster == "0000" & (attrId == "FF01" || attrId == "FF02"))
		// Parse battery level from hourly announcement message
		map = parseBattery(valueHex)
	else
		displayDebugLog("Unable to parse message")

	if (map != [:]) {
		displayInfoLog(map.descriptionText)
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

// Calculate temperature with 0.1 precision in C or F unit as set by hub location settings
private parseTemperature(description) {
	float temp = Integer.parseInt(description,16)/100
	def offset = tempOffset ? tempOffset : 0
	temp = (temp > 100) ? (temp - 655.35) : temp
	displayDebugLog("Raw reported temperature = ${temp}°C")
	temp = (temperatureScale == "F") ? ((temp * 1.8) + 32) + offset : temp + offset
	temp = temp.round(1)
	return [
		name: 'temperature',
		value: temp,
		unit: "${temperatureScale}",
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
		descriptionText: "Pressure is ${pressureval} ${PressureUnits}"
	]
}

// Convert raw 4 digit integer voltage value into percentage based on minVolts/maxVolts range
private parseBattery(description) {
	displayDebugLog("Battery parse string = ${description}")
	def rawValue = Integer.parseInt((description[8..9] + description[6..7]),16)
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
		descriptionText: descText
	]
	// lastCheckinEpoch is for apps that can use Epoch time/date and lastCheckinTime can be used with Hubitat Dashboard
	if (lastCheckinEnable) {
		sendEvent(name: "lastCheckinEpoch", value: now())
		sendEvent(name: "lastCheckinTime", value: new Date().toLocaleString())
	}
	return result
}

// installed() runs just after a sensor is paired
def installed() {
	displayDebugLog("Installing")
	state.prefsSetCount = 0
	init()
}

// configure() runs after installed() when a sensor is paired
def configure() {
	displayInfoLog("Configuring")
	init()
	state.prefsSetCount = 1
}

// updated() will run every time user saves preferences
def updated() {
	displayInfoLog("Updating preference settings")
	init()
	if (lastCheckinEnable)
		displayInfoLog("Last checkin events enabled")
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
}

def init() {
	if (!device.currentValue('batteryLastReplaced'))
		resetBatteryReplacedDate(true)
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
	def newlyPaired = paired ? " for newly paired sensor" : ""
	sendEvent(name: "batteryLastReplaced", value: new Date().format("MMM dd yyyy", location.timeZone))
	displayInfoLog("Setting Battery Last Replaced to current date${newlyPaired}")
}

private def displayDebugLog(message) {
	if (debugLogging) log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
	if (infoLogging || state.prefsSetCount != 1)
		log.info "${device.displayName}: ${message}"
}
