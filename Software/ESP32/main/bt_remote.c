/*
   Bluetooth Remote Controller for Reel-to-Reel tape players and cassette decks.

   This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
   CONDITIONS OF ANY KIND, either express or implied.
*/

/*
   TODO:
   Implement two-way communication to keep state machines synchronised between app and BT module.


   Figure out some nice method to handle all these different "REC STANDBY" methods,
   i.e. Technics RS1500 require REC+PAUSE+PLAY. Akai require REC+PAUSE.


*/

#include <stdint.h>
#include <string.h>
#include <stdbool.h>
#include <stdio.h>
#include "nvs.h"
#include "nvs_flash.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_bt.h"
#include "esp_bt_main.h"
#include "esp_gap_bt_api.h"
#include "esp_bt_device.h"
#include "esp_spp_api.h"

#include "time.h"
#include "sys/time.h"

#include "esp_system.h"
#include "esp_spi_flash.h"
#include "driver/gpio.h"
#include "driver/ledc.h"


/* LED PWM */
#define LEDC_HS_TIMER          LEDC_TIMER_0
#define LEDC_HS_MODE           LEDC_HIGH_SPEED_MODE
#define LEDC_HS_CH0_GPIO       (23)
#define LEDC_HS_CH0_CHANNEL    LEDC_CHANNEL_0
#define LEDC_DUTY_RES          (1023)


/* GPIO assignments. */
#define GPIO_PLAY_FWD	 21
#define GPIO_PLAY_REV	 16
#define GPIO_STOP		 17
#define GPIO_FFWD		 26 // Pin updated - IO5 was PU. Wire mod on rev.A boards.
#define GPIO_REW		 18
#define GPIO_REC		 19
#define GPIO_AUTO_MUTE	 13 // Pin updated - IO15 was PU. Wire mod on rev.A boards.
#define GPIO_PAUSE		 22
#define GPIO_BT_OPEN	 23 // Bluetooth connection LED indicator

#define CMD_REC_FWD      0x01
#define CMD_REC_REV      0x02
#define CMD_PAUSE        0x03
#define CMD_PLAY_FWD     0x04
#define CMD_PLAY_REV     0x05
#define CMD_STOP         0x06
#define CMD_FFWD         0x07
#define CMD_REW          0x08
#define CMD_AUTO_MUTE    0x09
#define CMD_TRANSPORT_FUNCTION  0xAA

#define CMD_SET_DELAY			 0xBA
#define CMD_SET_PWM				 0xCA
#define CMD_SET_REC_STDBY_MODE   0xBB
/* To engage recording standby on Technics machines,
   the following button sequence must be executed:
   REC + PAUSE + PLAY_FWD or PLAY_REV
   then, PLAY_FWD or PLAY_REV to start recording */
#define REC_STDBY_MODE_TECHNICS  0x01
/* To engage recording standby on Akai machines,
   the following button sequence must be executed:
   REC + PAUSE
   then, PLAY_FWD or PLAY_REV to start recording */
#define REC_STDBY_MODE_AKAI      0x02
#define REC_STDBY_MODE_PIONEER   0x04
#define REC_STDBY_MODE_TASCAM    0x08

#define ACK = 0xFE;

#define SPP_TAG				"BT_REMOTE"
#define SPP_SERVER_NAME		"R2R-BTR_SERVER"
#define DEVICE_NAME	 		"R2R-BTREMOTE"
#define SPP_SHOW_DATA 		 0
#define SPP_SHOW_SPEED		 1
#define SPP_SHOW_MODE		 SPP_SHOW_DATA    /*Choose show mode: show data or speed*/

#define APP_VERSION			"1.0.8. 26/09/2021"

static const esp_spp_mode_t esp_spp_mode = ESP_SPP_MODE_CB;

//static struct timeval time_new, time_old;

static const esp_spp_sec_t sec_mask = ESP_SPP_SEC_AUTHENTICATE;
static const esp_spp_role_t role_slave = ESP_SPP_ROLE_SLAVE;

uint8_t	 bt_data[64] = {0};
uint8_t	 bt_data_len = 0;
char	 bt_open = false;
uint8_t	 cmdDelay = 100; // For how long pin should be in active state (1...255ms)
//uint8_t	 cmdPWM = 100; // BT LED brightness (0...100%)
uint8_t	 play_direction = CMD_PLAY_FWD; // Keep track of play direction
uint8_t	 rec_stby_mode = REC_STDBY_MODE_TECHNICS; // Variable to store record standby mode received from the app
unsigned int ledc_duty = LEDC_DUTY_RES;

// Tape transport control state machine
static void send_ctrl(char data)
{
		switch (data) {
			case CMD_PLAY_FWD:
		        ESP_LOGI(SPP_TAG, "PLAY_FWD received");
				gpio_set_level(GPIO_PLAY_FWD, 1);
				vTaskDelay(cmdDelay / portTICK_PERIOD_MS);
				gpio_set_level(GPIO_PLAY_FWD, 0);
				play_direction = CMD_PLAY_FWD;
				break;
			case CMD_PLAY_REV:
		        ESP_LOGI(SPP_TAG, "PLAY_REV received");
				gpio_set_level(GPIO_PLAY_REV, 1);
				vTaskDelay(cmdDelay / portTICK_PERIOD_MS);
				gpio_set_level(GPIO_PLAY_REV, 0);
				play_direction = CMD_PLAY_REV;
				break;
			case CMD_STOP:
		        ESP_LOGI(SPP_TAG, "STOP received");
				gpio_set_level(GPIO_STOP, 1);
				vTaskDelay(cmdDelay / portTICK_PERIOD_MS);
				gpio_set_level(GPIO_STOP, 0);
				break;
			case CMD_FFWD:
		        ESP_LOGI(SPP_TAG, "FFWD received");
				gpio_set_level(GPIO_FFWD, 1);
				vTaskDelay(cmdDelay / portTICK_PERIOD_MS);
				gpio_set_level(GPIO_FFWD, 0);
				break;
			case CMD_REW:
		        ESP_LOGI(SPP_TAG, "REW received");
				gpio_set_level(GPIO_REW, 1);
				vTaskDelay(cmdDelay / portTICK_PERIOD_MS);
				gpio_set_level(GPIO_REW, 0);
				break;
			case CMD_PAUSE:
		        ESP_LOGI(SPP_TAG, "PAUSE received");
				gpio_set_level(GPIO_PAUSE, 1);
				vTaskDelay(cmdDelay / portTICK_PERIOD_MS);
				gpio_set_level(GPIO_PAUSE, 0);
				break;
			case CMD_AUTO_MUTE:
		        ESP_LOGI(SPP_TAG, "AUTO_MUTE received");
				gpio_set_level(GPIO_AUTO_MUTE, 1);
				vTaskDelay(cmdDelay / portTICK_PERIOD_MS);
				gpio_set_level(GPIO_AUTO_MUTE, 0);
				break;
			case CMD_REC_FWD:
		        ESP_LOGI(SPP_TAG, "REC-FWD received");
		        switch(rec_stby_mode){
					case REC_STDBY_MODE_TECHNICS:
						gpio_set_level(GPIO_REC, 1);
						gpio_set_level(GPIO_PAUSE, 1);
						gpio_set_level(GPIO_PLAY_FWD, 1);
				        ESP_LOGI(SPP_TAG, "REC_STDBY_MODE_TECHNICS");
						break;
					case REC_STDBY_MODE_AKAI:
						gpio_set_level(GPIO_REC, 1);
						gpio_set_level(GPIO_PAUSE, 1);
				        ESP_LOGI(SPP_TAG, "REC_STDBY_MODE_AKAI");
						break;
					case REC_STDBY_MODE_PIONEER:
						gpio_set_level(GPIO_PAUSE, 1);
						gpio_set_level(GPIO_REC, 1);
						gpio_set_level(GPIO_PLAY_FWD, 1);
				        ESP_LOGI(SPP_TAG, "REC_STDBY_MODE_PIONEER");
						break;
					case REC_STDBY_MODE_TASCAM:
						gpio_set_level(GPIO_PAUSE, 1);
						gpio_set_level(GPIO_REC, 1);
						gpio_set_level(GPIO_PLAY_FWD, 1);
				        ESP_LOGI(SPP_TAG, "REC_STDBY_MODE_TASCAM");
						break;
					default:
						gpio_set_level(GPIO_REC, 0);
						gpio_set_level(GPIO_PAUSE, 0);
						gpio_set_level(GPIO_PLAY_FWD, 0);
						gpio_set_level(GPIO_PLAY_REV, 0);
				        ESP_LOGW(SPP_TAG, "REC_STDBY_MODE_UNKNOWN");
						break;
		        }
				vTaskDelay(cmdDelay / portTICK_PERIOD_MS);
				gpio_set_level(GPIO_PLAY_FWD, 0);
				gpio_set_level(GPIO_PAUSE, 0);
				gpio_set_level(GPIO_REC, 0);
				break;
				case CMD_REC_REV:
			        ESP_LOGI(SPP_TAG, "REC-REV received");
			        switch(rec_stby_mode){
						case REC_STDBY_MODE_TECHNICS:
							gpio_set_level(GPIO_REC, 1);
							gpio_set_level(GPIO_PAUSE, 1);
							gpio_set_level(GPIO_PLAY_REV, 1);
					        ESP_LOGI(SPP_TAG, "REC_STDBY_MODE_TECHNICS");
							break;
						case REC_STDBY_MODE_AKAI:
							gpio_set_level(GPIO_REC, 1);
							gpio_set_level(GPIO_PAUSE, 1);
					        ESP_LOGI(SPP_TAG, "REC_STDBY_MODE_AKAI");
							break;
						case REC_STDBY_MODE_PIONEER:
							gpio_set_level(GPIO_PAUSE, 1);
							gpio_set_level(GPIO_REC, 1);
							gpio_set_level(GPIO_PLAY_REV, 1);
					        ESP_LOGI(SPP_TAG, "REC_STDBY_MODE_PIONEER");
							break;
						case REC_STDBY_MODE_TASCAM:
							gpio_set_level(GPIO_PAUSE, 1);
							gpio_set_level(GPIO_REC, 1);
							gpio_set_level(GPIO_PLAY_REV, 1);
					        ESP_LOGI(SPP_TAG, "REC_STDBY_MODE_TASCAM");
							break;
						default:
							gpio_set_level(GPIO_REC, 0);
							gpio_set_level(GPIO_PAUSE, 0);
							gpio_set_level(GPIO_PLAY_FWD, 0);
							gpio_set_level(GPIO_PLAY_REV, 0);
					        ESP_LOGW(SPP_TAG, "REC_STDBY_MODE_UNKNOWN");
							break;
			        }
					vTaskDelay(cmdDelay / portTICK_PERIOD_MS);
					gpio_set_level(GPIO_PLAY_REV, 0);
					gpio_set_level(GPIO_PAUSE, 0);
					gpio_set_level(GPIO_REC, 0);
					break;
			default: // Error - Switch all off
		        ESP_LOGE(SPP_TAG, "UNKNOWN_COMMAND received");
				gpio_set_level(GPIO_PLAY_FWD, 0);
				gpio_set_level(GPIO_PLAY_REV, 0);
				gpio_set_level(GPIO_STOP, 0);
				gpio_set_level(GPIO_FFWD, 0);
				gpio_set_level(GPIO_REW, 0);
				gpio_set_level(GPIO_REC, 0);
				gpio_set_level(GPIO_AUTO_MUTE, 0);
				gpio_set_level(GPIO_PAUSE, 0);
				break;

		}
		bt_data_len = 0; // Reset data pointer
}

static void esp_spp_cb(esp_spp_cb_event_t event, esp_spp_cb_param_t *param)
{
    switch (event) {
    case ESP_SPP_INIT_EVT:
        ESP_LOGI(SPP_TAG, "ESP_SPP_INIT_EVT");
        esp_bt_dev_set_device_name(DEVICE_NAME);
        esp_bt_gap_set_scan_mode(ESP_BT_CONNECTABLE, ESP_BT_GENERAL_DISCOVERABLE);
        esp_spp_start_srv(sec_mask,role_slave, 0, SPP_SERVER_NAME);
        break;
    case ESP_SPP_DISCOVERY_COMP_EVT:
        ESP_LOGI(SPP_TAG, "ESP_SPP_DISCOVERY_COMP_EVT");
        break;
    case ESP_SPP_OPEN_EVT:
        ESP_LOGI(SPP_TAG, "ESP_SPP_OPEN_EVT");
        bt_open = true;
        break;
    case ESP_SPP_CLOSE_EVT:
        ESP_LOGI(SPP_TAG, "ESP_SPP_CLOSE_EVT");
        bt_open = false;
        break;
    case ESP_SPP_START_EVT:
        ESP_LOGI(SPP_TAG, "ESP_SPP_START_EVT");
        break;
    case ESP_SPP_CL_INIT_EVT:
        ESP_LOGI(SPP_TAG, "ESP_SPP_CL_INIT_EVT");
        break;
    case ESP_SPP_DATA_IND_EVT:
        ESP_LOGI(SPP_TAG, "ESP_SPP_DATA_IND_EVT len=%d handle=%d",
                 param->data_ind.len, param->data_ind.handle);
        esp_log_buffer_hex("",param->data_ind.data,param->data_ind.len);
        bt_data_len = param->data_ind.len;
        if (bt_data_len) {
        	// Copy received data to the buffer
            for(int i = 0; i < bt_data_len; i++) {
            	bt_data[i] = param->data_ind.data[i];
            }
            // Parse the buffer
            // Data will always be received in pairs: command + data
            for(int i = 0; i < bt_data_len; i++) {
            	switch(bt_data[i]){
            	case CMD_SET_REC_STDBY_MODE:
            		i++; // increment pointer
            		rec_stby_mode = bt_data[i]; // read data
                    ESP_LOGI(SPP_TAG, "Setting rec_stby to: 0x%02X", rec_stby_mode);
            		break;
            	case CMD_SET_DELAY:
            		i++; // increment pointer
            		cmdDelay = bt_data[i]; // read data
                    ESP_LOGI(SPP_TAG, "Setting delay to: %d ms", cmdDelay);
            		break;
            	case CMD_SET_PWM:
            		i++; // increment pointer
            		ledc_duty = ((LEDC_DUTY_RES / 100) * bt_data[i]); // read data
                    ESP_LOGI(SPP_TAG, "Setting LED brightness to: %d%%", (ledc_duty / 10));
            		break;
            	case CMD_TRANSPORT_FUNCTION:
            		i++; // increment pointer
            		send_ctrl(bt_data[i]); // read data
            		break;
            	default:
            		break;
            	}

            }
        }
        break;
    case ESP_SPP_CONG_EVT:
        ESP_LOGI(SPP_TAG, "ESP_SPP_CONG_EVT");
        break;
    case ESP_SPP_WRITE_EVT:
        ESP_LOGI(SPP_TAG, "ESP_SPP_WRITE_EVT");
        break;
    case ESP_SPP_SRV_OPEN_EVT:
        ESP_LOGI(SPP_TAG, "ESP_SPP_SRV_OPEN_EVT");
        //gettimeofday(&time_old, NULL);
        bt_open = true;
        break;
    case ESP_SPP_SRV_STOP_EVT:
        ESP_LOGI(SPP_TAG, "ESP_SPP_SRV_STOP_EVT");
        bt_open = false;
        break;
    case ESP_SPP_UNINIT_EVT:
        ESP_LOGI(SPP_TAG, "ESP_SPP_UNINIT_EVT");
        break;
    default:
        break;
    }
}

void esp_bt_gap_cb(esp_bt_gap_cb_event_t event, esp_bt_gap_cb_param_t *param)
{
    switch (event) {
    case ESP_BT_GAP_AUTH_CMPL_EVT:{
        if (param->auth_cmpl.stat == ESP_BT_STATUS_SUCCESS) {
            ESP_LOGI(SPP_TAG, "authentication success: %s", param->auth_cmpl.device_name);
            esp_log_buffer_hex(SPP_TAG, param->auth_cmpl.bda, ESP_BD_ADDR_LEN);
        } else {
            ESP_LOGE(SPP_TAG, "authentication failed, status:%d", param->auth_cmpl.stat);
        }
        break;
    }
    case ESP_BT_GAP_PIN_REQ_EVT:{
        ESP_LOGI(SPP_TAG, "ESP_BT_GAP_PIN_REQ_EVT min_16_digit:%d", param->pin_req.min_16_digit);
        if (param->pin_req.min_16_digit) {
            ESP_LOGI(SPP_TAG, "Input pin code: 0000 0000 0000 0000");
            esp_bt_pin_code_t pin_code = {0};
            esp_bt_gap_pin_reply(param->pin_req.bda, true, 16, pin_code);
        } else {
            ESP_LOGI(SPP_TAG, "Input pin code: 1234");
            esp_bt_pin_code_t pin_code;
            pin_code[0] = '1';
            pin_code[1] = '2';
            pin_code[2] = '3';
            pin_code[3] = '4';
            esp_bt_gap_pin_reply(param->pin_req.bda, true, 4, pin_code);
        }
        break;
    }

#if (CONFIG_BT_SSP_ENABLED == true)
    case ESP_BT_GAP_CFM_REQ_EVT:
        ESP_LOGI(SPP_TAG, "ESP_BT_GAP_CFM_REQ_EVT Please compare the numeric value: %d", param->cfm_req.num_val);
        esp_bt_gap_ssp_confirm_reply(param->cfm_req.bda, true);
        break;
    case ESP_BT_GAP_KEY_NOTIF_EVT:
        ESP_LOGI(SPP_TAG, "ESP_BT_GAP_KEY_NOTIF_EVT passkey:%d", param->key_notif.passkey);
        break;
    case ESP_BT_GAP_KEY_REQ_EVT:
        ESP_LOGI(SPP_TAG, "ESP_BT_GAP_KEY_REQ_EVT Please enter passkey!");
        break;
#endif

    case ESP_BT_GAP_MODE_CHG_EVT:
        ESP_LOGI(SPP_TAG, "ESP_BT_GAP_MODE_CHG_EVT mode:%d", param->mode_chg.mode);
        break;

    default: {
        ESP_LOGI(SPP_TAG, "event: %d", event);
        break;
    }
    }
    return;
}

void getMAC_Address(void){
    uint8_t mac_addr[6] = {0};
    esp_err_t ret = ESP_OK;
    //Get base MAC address from EFUSE BLK0
    ret = esp_efuse_mac_get_default(mac_addr);
    if (ret != ESP_OK) {
        ESP_LOGE(SPP_TAG, "Failed to get base MAC address from EFUSE BLK0. (%s)", esp_err_to_name(ret));
        ESP_LOGE(SPP_TAG, "Aborting");
        abort();
    } else {
        ESP_LOGI(SPP_TAG, "Base MAC Address read from EFUSE BLK0");
        ESP_LOGI("Base MAC", "\"%02X:%02X:%02X:%02X:%02X:%02X\"",
        		mac_addr[0], mac_addr[1], mac_addr[2],
				mac_addr[3], mac_addr[4], mac_addr[5]);
    }
    //Get MAC address for Bluetooth
    ESP_ERROR_CHECK(esp_read_mac(mac_addr, ESP_MAC_BT));
    ESP_LOGI("BT MAC", "   %02X:%02X:%02X:%02X:%02X:%02X",
             mac_addr[0], mac_addr[1], mac_addr[2],
             mac_addr[3], mac_addr[4], mac_addr[5]);
}

void app_main(void)
{
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK( ret );

    ESP_ERROR_CHECK(esp_bt_controller_mem_release(ESP_BT_MODE_BLE));

    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    if ((ret = esp_bt_controller_init(&bt_cfg)) != ESP_OK) {
        ESP_LOGE(SPP_TAG, "%s initialise controller failed: %s\n", __func__, esp_err_to_name(ret));
        return;
    }

    if ((ret = esp_bt_controller_enable(ESP_BT_MODE_CLASSIC_BT)) != ESP_OK) {
        ESP_LOGE(SPP_TAG, "%s enable controller failed: %s\n", __func__, esp_err_to_name(ret));
        return;
    }

    if ((ret = esp_bluedroid_init()) != ESP_OK) {
        ESP_LOGE(SPP_TAG, "%s initialise bluedroid failed: %s\n", __func__, esp_err_to_name(ret));
        return;
    }

    if ((ret = esp_bluedroid_enable()) != ESP_OK) {
        ESP_LOGE(SPP_TAG, "%s enable bluedroid failed: %s\n", __func__, esp_err_to_name(ret));
        return;
    }

    if ((ret = esp_bt_gap_register_callback(esp_bt_gap_cb)) != ESP_OK) {
        ESP_LOGE(SPP_TAG, "%s gap register failed: %s\n", __func__, esp_err_to_name(ret));
        return;
    }

    if ((ret = esp_spp_register_callback(esp_spp_cb)) != ESP_OK) {
        ESP_LOGE(SPP_TAG, "%s spp register failed: %s\n", __func__, esp_err_to_name(ret));
        return;
    }

    if ((ret = esp_spp_init(esp_spp_mode)) != ESP_OK) {
        ESP_LOGE(SPP_TAG, "%s spp init failed: %s\n", __func__, esp_err_to_name(ret));
        return;
    }

#if (CONFIG_BT_SSP_ENABLED == true)
    /* Set default parameters for Secure Simple Pairing */
    esp_bt_sp_param_t param_type = ESP_BT_SP_IOCAP_MODE;
    esp_bt_io_cap_t iocap = ESP_BT_IO_CAP_IO;
    esp_bt_gap_set_security_param(param_type, &iocap, sizeof(uint8_t));
#endif

    /*
     * Set default parameters for Legacy Pairing
     * Use variable pin, input pin code when pairing
     */
    esp_bt_pin_type_t pin_type = ESP_BT_PIN_TYPE_VARIABLE;
    esp_bt_pin_code_t pin_code;
    esp_bt_gap_set_pin(pin_type, 0, pin_code);

    printf("\n------------------------------------------------------\n");
    ESP_LOGI(SPP_TAG, "Firmware version: %s", APP_VERSION);
    //printf("Firmware version: %s", APP_VERSION);
    printf("------------------------------------------------------\n\n");

    /*
     * Prepare and set configuration of timers
     * that will be used by LED Controller
     */
    ledc_timer_config_t ledc_timer = {
        .duty_resolution = LEDC_TIMER_10_BIT, // resolution of PWM duty
        .freq_hz = 5000,                      // frequency of PWM signal
        .speed_mode = LEDC_HS_MODE,           // timer mode
        .timer_num = LEDC_HS_TIMER,            // timer index
        .clk_cfg = LEDC_AUTO_CLK,              // Auto select the source clock
    };
    // Set configuration of timer0 for high speed channels
    ledc_timer_config(&ledc_timer);
    /*
     * Prepare individual configuration
     * for each channel of LED Controller
     * by selecting:
     * - controller's channel number
     * - output duty cycle, set initially to 0
     * - GPIO number where LED is connected to
     * - speed mode, either high or low
     * - timer servicing selected channel
     *   Note: if different channels use one timer,
     *         then frequency and bit_num of these channels
     *         will be the same
     */
    ledc_channel_config_t ledc_channel = {
            .channel    = LEDC_HS_CH0_CHANNEL,
            .duty       = 0,
            .gpio_num   = LEDC_HS_CH0_GPIO,
            .speed_mode = LEDC_HS_MODE,
            .hpoint     = 0,
            .timer_sel  = LEDC_HS_TIMER
        };
    ledc_channel_config(&ledc_channel);




    /* Configure the IOMUX register for GPIO */
    printf("Selecting GPIOs...\n");
    gpio_pad_select_gpio(GPIO_PLAY_FWD);
    gpio_pad_select_gpio(GPIO_PLAY_REV);
    gpio_pad_select_gpio(GPIO_STOP);
    gpio_pad_select_gpio(GPIO_FFWD);
    gpio_pad_select_gpio(GPIO_REW);
    gpio_pad_select_gpio(GPIO_REC);
    gpio_pad_select_gpio(GPIO_AUTO_MUTE);
    gpio_pad_select_gpio(GPIO_PAUSE);
    //gpio_pad_select_gpio(GPIO_BT_OPEN);

    printf("Done!\n\n");

    /* Set the GPIO as input/output, in case we need feedback to the app in the future */
    printf("Setting GPIOs to outputs...\n");
    gpio_set_direction(GPIO_PLAY_FWD, GPIO_MODE_INPUT_OUTPUT);
    gpio_set_direction(GPIO_PLAY_REV, GPIO_MODE_INPUT_OUTPUT);
    gpio_set_direction(GPIO_STOP, GPIO_MODE_INPUT_OUTPUT);
    gpio_set_direction(GPIO_FFWD, GPIO_MODE_INPUT_OUTPUT);
    gpio_set_direction(GPIO_REW, GPIO_MODE_INPUT_OUTPUT);
    gpio_set_direction(GPIO_REC, GPIO_MODE_INPUT_OUTPUT);
    gpio_set_direction(GPIO_AUTO_MUTE, GPIO_MODE_INPUT_OUTPUT);
    gpio_set_direction(GPIO_PAUSE, GPIO_MODE_INPUT_OUTPUT);
    //gpio_set_direction(GPIO_BT_OPEN, GPIO_MODE_INPUT_OUTPUT);

    printf("Done!\n\n");
    printf("%s is running...\n\n", SPP_TAG);

    getMAC_Address(); // Read and display BT MAC address.

    while (1) {
/*
        gpio_set_level(GPIO_BT_OPEN, 1);
        vTaskDelay(cmdDelay / portTICK_PERIOD_MS);
        gpio_set_level(GPIO_BT_OPEN, 0);
        if(!bt_open) {
            vTaskDelay((1000 - cmdDelay) / portTICK_PERIOD_MS); // Fast blink when not connected
        }else{
            vTaskDelay((5000 - cmdDelay) / portTICK_PERIOD_MS); // Slow blink when connected

        }
*/
		ledc_set_duty(ledc_channel.speed_mode, ledc_channel.channel, ledc_duty);
		ledc_update_duty(ledc_channel.speed_mode, ledc_channel.channel);
		vTaskDelay(cmdDelay / portTICK_PERIOD_MS);
		ledc_set_duty(ledc_channel.speed_mode, ledc_channel.channel, 0);
		ledc_update_duty(ledc_channel.speed_mode, ledc_channel.channel);
		if(!bt_open) {
			vTaskDelay((1000 - cmdDelay) / portTICK_PERIOD_MS); // Fast blink when not connected
		}else{
			vTaskDelay((5000 - cmdDelay) / portTICK_PERIOD_MS); // Slow blink when connected

		}
    }
}
