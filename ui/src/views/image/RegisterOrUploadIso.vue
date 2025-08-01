// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

<template>
  <div
    class="form-layout"
    @keyup.ctrl.enter="handleSubmit">
    <span v-if="uploadPercentage > 0">
      <loading-outlined />
      {{ $t('message.upload.file.processing') }}
      <a-progress :percent="uploadPercentage" />
    </span>
    <a-spin :spinning="loading" v-else>
      <a-form
        :ref="formRef"
        :model="form"
        :rules="rules"
        @finish="handleSubmit"
        v-ctrl-enter="handleSubmit"
        layout="vertical">
        <a-form-item
          v-if="currentForm === 'Create'"
          ref="url"
          name="url">
          <template #label>
            <tooltip-label :title="$t('label.url')" :tooltip="apiParams.url.description"/>
          </template>
          <a-input
            v-focus="currentForm === 'Create'"
            v-model:value="form.url"
            :placeholder="apiParams.url.description" />
        </a-form-item>
        <a-form-item
          v-if="currentForm === 'Upload'"
          ref="file"
          name="file"
          :label="$t('label.templatefileupload')">
          <a-upload-dragger
            :multiple="false"
            :fileList="fileList"
            @remove="handleRemove"
            :beforeUpload="beforeUpload"
            v-model:value="form.file">
            <p class="ant-upload-drag-icon">
              <cloud-upload-outlined />
            </p>
            <p class="ant-upload-text" v-if="fileList.length === 0">
              {{ $t('label.volume.volumefileupload.description') }}
            </p>
          </a-upload-dragger>
        </a-form-item>
        <a-form-item ref="name" name="name">
          <template #label>
            <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
          </template>
          <a-input
            v-model:value="form.name"
            :placeholder="apiParams.name.description"
            v-focus="currentForm !== 'Create'" />
        </a-form-item>
        <a-form-item ref="displaytext" name="displaytext">
          <template #label>
            <tooltip-label :title="$t('label.displaytext')" :tooltip="apiParams.displaytext.description"/>
          </template>
          <a-input
            v-model:value="form.displaytext"
            :placeholder="apiParams.displaytext.description" />
        </a-form-item>

        <a-form-item ref="directdownload" name="directdownload" v-if="allowed && currentForm !== 'Upload'">
          <template #label>
            <tooltip-label :title="$t('label.directdownload')" :tooltip="apiParams.directdownload.description"/>
          </template>
          <a-switch v-model:checked="form.directdownload"/>
        </a-form-item>

        <a-form-item ref="checksum" name="checksum">
          <template #label>
            <tooltip-label :title="$t('label.checksum')" :tooltip="apiParams.checksum.description"/>
          </template>
          <a-input
            v-model:value="form.checksum"
            :placeholder="apiParams.checksum.description" />
        </a-form-item>

        <a-form-item ref="zoneid" name="zoneid">
          <template #label>
            <tooltip-label :title="$t('label.zoneid')" :tooltip="apiParams.zoneid.description"/>
          </template>
          <a-select
            v-model:value="form.zoneid"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return  option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            :loading="zoneLoading"
            :placeholder="apiParams.zoneid.description">
            <a-select-option :value="opt.id" v-for="opt in zones" :key="opt.id" :label="opt.name || opt.description">
              <span>
                <resource-icon v-if="opt.icon" :image="opt.icon.base64image" size="1x" style="margin-right: 5px"/>
                <global-outlined v-else style="margin-right: 5px" />
                {{ opt.name || opt.description }}
              </span>
            </a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item name="domainid" ref="domainid" v-if="'listDomains' in $store.getters.apis">
          <template #label>
            <tooltip-label :title="$t('label.domainid')" :tooltip="apiParams.domainid.description"/>
          </template>
          <a-select
            v-model:value="form.domainid"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            :loading="domainLoading"
            :placeholder="apiParams.domainid.description"
            @change="val => { handleDomainChange(val) }">
            <a-select-option v-for="(opt, optIndex) in this.domains" :key="optIndex" :label="opt.path || opt.name || opt.description" :value="opt.id">
              <span>
                <resource-icon v-if="opt && opt.icon" :image="opt.icon.base64image" size="1x" style="margin-right: 5px"/>
                <block-outlined v-else style="margin-right: 5px" />
                {{ opt.path || opt.name || opt.description }}
              </span>
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item name="account" ref="account" v-if="domainid">
          <template #label>
            <tooltip-label :title="$t('label.account')" :tooltip="apiParams.account.description"/>
          </template>
          <a-select
            v-model:value="form.account"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.value.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            :placeholder="apiParams.account.description"
            @change="val => { handleAccountChange(val) }">
            <a-select-option v-for="(acc, index) in accounts" :value="acc.name" :key="index">
              {{ acc.name }}
            </a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item ref="bootable" name="bootable">
          <template #label>
            <tooltip-label :title="$t('label.bootable')" :tooltip="apiParams.bootable.description"/>
          </template>
          <a-switch v-model:checked="form.bootable" />
        </a-form-item>

        <a-form-item ref="ostypeid" name="ostypeid" v-if="form.bootable">
          <template #label>
            <tooltip-label :title="$t('label.ostypeid')" :tooltip="apiParams.ostypeid.description"/>
          </template>
          <a-select
            v-model:value="form.ostypeid"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return  option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            :loading="osTypeLoading"
            :placeholder="apiParams.ostypeid.description">
            <a-select-option :value="opt.id" v-for="(opt, optIndex) in osTypes" :key="optIndex" :label="opt.name">
              <span>
                <resource-icon v-if="opt.icon" :image="opt.icon.base64image" size="1x" style="margin-right: 5px"/>
                <global-outlined v-else style="margin-right: 5px" />
                {{ opt.name }}
              </span>
            </a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item
          name="arch"
          ref="arch">
          <template #label>
            <tooltip-label :title="$t('label.arch')" :tooltip="apiParams.arch.description"/>
          </template>
          <a-select
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            v-model:value="form.arch"
            :placeholder="apiParams.arch.description">
            <a-select-option v-for="opt in architectureTypes.opts" :key="opt.id">
              {{ opt.name || opt.description }}
            </a-select-option>
          </a-select>
        </a-form-item>

        <a-row :gutter="12">
          <a-col :md="24" :lg="12">
            <a-form-item
              name="userdataid"
              ref="userdataid">
              <template #label>
                <tooltip-label :title="$t('label.user.data')" :tooltip="linkUserDataParams.userdataid.description"/>
              </template>
              <a-select
                showSearch
                optionFilterProp="label"
                :filterOption="(input, option) => {
                  return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }"
                v-model:value="userdataid"
                :placeholder="linkUserDataParams.userdataid.description"
                :loading="userdata.loading">
                <a-select-option v-for="opt in userdata.opts" :key="opt.id" :label="opt.name || opt.description">
                  {{ opt.name || opt.description }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :md="24" :lg="12">
            <a-form-item ref="userdatapolicy" name="userdatapolicy">
              <template #label>
                <tooltip-label :title="$t('label.user.data.policy')" :tooltip="linkUserDataParams.userdatapolicy.description"/>
              </template>
              <a-select
                showSearch
                v-model:value="userdatapolicy"
                :placeholder="linkUserDataParams.userdatapolicy.description"
                optionFilterProp="label"
                :filterOption="(input, option) => {
                  return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }" >
                <a-select-option v-for="opt in userdatapolicylist.opts" :key="opt.id" :label="opt.id || opt.description">
                  {{ opt.id || opt.description }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="12">
          <a-col :md="24" :lg="12">
            <a-form-item ref="isdynamicallyscalable" name="isdynamicallyscalable">
              <template #label>
                <tooltip-label :title="$t('label.isdynamicallyscalable')" :tooltip="apiParams.isdynamicallyscalable.description"/>
              </template>
              <a-switch v-model:checked="form.isdynamicallyscalable" />
            </a-form-item>
            <a-form-item
              ref="ispublic"
              name="ispublic"
              v-if="$store.getters.userInfo.roletype === 'Admin' || $store.getters.features.userpublictemplateenabled" >
              <template #label>
                <tooltip-label :title="$t('label.ispublic')" :tooltip="apiParams.ispublic.description"/>
              </template>
              <a-switch v-model:checked="form.ispublic" />
            </a-form-item>
            <a-form-item ref="passwordenabled" name="passwordenabled" v-if="currentForm === 'Create'">
              <template #label>
                <tooltip-label :title="$t('label.passwordenabled')" :tooltip="apiParams.passwordenabled.description"/>
              </template>
              <a-switch v-model:checked="form.passwordenabled" />
            </a-form-item>
          </a-col>
          <a-col :md="24" :lg="12">
            <a-form-item ref="isextractable" name="isextractable">
              <template #label>
                <tooltip-label :title="$t('label.isextractable')" :tooltip="apiParams.isextractable.description"/>
              </template>
              <a-switch v-model:checked="form.isextractable" />
            </a-form-item>
            <a-form-item ref="isfeatured" name="isfeatured" v-if="$store.getters.userInfo.roletype === 'Admin'">
              <template #label>
                <tooltip-label :title="$t('label.isfeatured')" :tooltip="apiParams.isfeatured.description"/>
              </template>
              <a-switch v-model:checked="form.isfeatured" />
            </a-form-item>
          </a-col>
        </a-row>

        <div :span="24" class="action-button">
          <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
          <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-spin>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import store from '@/store'
import { axios } from '../../utils/request'
import { mixinForm } from '@/utils/mixin'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'RegisterIso',
  mixins: [mixinForm],
  props: {
    resource: {
      type: Object,
      required: true
    },
    action: {
      type: Object,
      required: true
    }
  },
  components: {
    ResourceIcon,
    TooltipLabel
  },
  data () {
    return {
      fileList: [],
      zones: [],
      osTypes: [],
      zoneLoading: false,
      osTypeLoading: false,
      userdata: {},
      userdataid: null,
      userdatapolicy: null,
      userdatapolicylist: {},
      loading: false,
      allowed: false,
      uploadParams: null,
      uploadPercentage: 0,
      currentForm: ['plus-outlined', 'PlusOutlined'].includes(this.action.currentAction.icon) ? 'Create' : 'Upload',
      domains: [],
      accounts: [],
      domainLoading: false,
      domainid: null,
      account: null,
      architectureTypes: {}
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('registerIso')
    this.linkUserDataParams = this.$getApiParams('linkUserDataToTemplate')
  },
  created () {
    this.initForm()
    this.zones = []
    if (this.$store.getters.userInfo.roletype === 'Admin' && this.currentForm === 'Create') {
      this.zones = [
        {
          id: '-1',
          name: this.$t('label.all.zone')
        }
      ]
    }
    this.fetchData()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        bootable: true,
        isextractable: false,
        ispublic: false,
        passwordenabled: false,
        isdynamicallyscalable: false
      })
      this.rules = reactive({
        url: [{ required: true, message: this.$t('label.upload.iso.from.local') }],
        file: [{ required: true, message: this.$t('message.error.required.input') }],
        name: [{ required: true, message: this.$t('message.error.required.input') }],
        zoneid: [{ required: true, message: this.$t('message.error.select') }],
        ostypeid: [{ required: true, message: this.$t('message.error.select') }]
      })
    },
    fetchData () {
      this.fetchZoneData()
      this.fetchOsType()
      this.architectureTypes.opts = this.$fetchCpuArchitectureTypes()
      this.fetchUserData()
      this.fetchUserdataPolicy()
      if ('listDomains' in this.$store.getters.apis) {
        this.fetchDomains()
      }
    },
    fetchZoneData () {
      const params = {}
      params.showicon = true

      this.zoneLoading = true
      if (store.getters.userInfo.roletype === 'Admin') {
        this.allowed = true
      }
      getAPI('listZones', params).then(json => {
        const listZones = json.listzonesresponse.zone
        if (listZones) {
          this.zones = this.zones.concat(listZones)
          this.zones = this.zones.filter(zone => zone.type !== 'Edge')
        }
      }).finally(() => {
        this.zoneLoading = false
        this.form.zoneid = (this.zones[0].id ? this.zones[0].id : '')
      })
    },
    fetchOsType () {
      this.osTypeLoading = true

      getAPI('listOsTypes').then(json => {
        const listOsTypes = json.listostypesresponse.ostype
        this.osTypes = this.osTypes.concat(listOsTypes)
      }).finally(() => {
        this.osTypeLoading = false
        this.form.ostypeid = this.osTypes[0].id
      })
    },
    fetchUserData () {
      const params = {}
      params.listAll = true

      this.userdata.opts = []
      this.userdata.loading = true

      getAPI('listUserData', params).then(json => {
        const listUserdata = json.listuserdataresponse.userdata
        this.userdata.opts = listUserdata
      }).finally(() => {
        this.userdata.loading = false
      })
    },
    fetchUserdataPolicy () {
      const userdataPolicy = []
      userdataPolicy.push({
        id: 'allowoverride',
        description: 'allowoverride'
      })
      userdataPolicy.push({
        id: 'append',
        description: 'append'
      })
      userdataPolicy.push({
        id: 'denyoverride',
        description: 'denyoverride'
      })
      this.userdatapolicylist.opts = userdataPolicy
    },
    handleRemove (file) {
      const index = this.fileList.indexOf(file)
      const newFileList = this.fileList.slice()
      newFileList.splice(index, 1)
      this.fileList = newFileList
      this.form.file = undefined
    },
    beforeUpload (file) {
      this.fileList = [file]
      this.form.file = file
      return false
    },
    handleUpload () {
      const { fileList } = this
      if (this.fileList.length > 1) {
        this.$notification.error({
          message: this.$t('message.upload.iso.failed'),
          description: this.$t('message.error.upload.iso.description'),
          duration: 0
        })
      }
      const formData = new FormData()
      fileList.forEach(file => {
        formData.append('files[]', file)
      })
      this.uploadPercentage = 0
      axios.post(this.uploadParams.postURL,
        formData,
        {
          headers: {
            'content-type': 'multipart/form-data',
            'x-signature': this.uploadParams.signature,
            'x-expires': this.uploadParams.expires,
            'x-metadata': this.uploadParams.metadata
          },
          onUploadProgress: (progressEvent) => {
            this.uploadPercentage = Number(parseFloat(100 * progressEvent.loaded / progressEvent.total).toFixed(1))
          },
          timeout: 86400000
        }).then((json) => {
        this.$notification.success({
          message: this.$t('message.success.upload'),
          description: this.$t('message.success.upload.description')
        })
        this.closeAction()
        this.$emit('refresh-data')
      }).catch(e => {
        this.$notification.error({
          message: this.$t('message.upload.failed'),
          description: `${this.$t('message.upload.iso.failed.description')} -  ${e}`,
          duration: 0
        })
      })
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        const formRaw = toRaw(this.form)
        const values = this.handleRemoveFields(formRaw)
        const params = {}
        for (const key in values) {
          const input = values[key]
          if (input === undefined) {
            continue
          }
          if (key === 'file') {
            continue
          }
          switch (key) {
            case 'zoneid':
              var zone = this.zones.filter(zone => zone.id === input)
              params[key] = zone[0].id
              break
            case 'ostypeid':
              params[key] = input
              break
            default:
              params[key] = input
              break
          }
        }

        if (this.currentForm === 'Create') {
          this.loading = true
          postAPI('registerIso', params).then(json => {
            if (this.userdataid !== null) {
              this.linkUserdataToTemplate(this.userdataid, json.registerisoresponse.iso[0].id, this.userdatapolicy)
            }
            this.$notification.success({
              message: this.$t('label.action.register.iso'),
              description: `${this.$t('message.success.register.iso')} ${params.name}`
            })
            this.closeAction()
            this.$emit('refresh-data')
          }).catch(error => {
            this.$notifyError(error)
          }).finally(() => {
            this.loading = false
          })
        } else {
          if (this.fileList.length !== 1) {
            return
          }
          params.format = 'ISO'
          this.loading = true
          getAPI('getUploadParamsForIso', params).then(json => {
            this.uploadParams = (json.postuploadisoresponse && json.postuploadisoresponse.getuploadparams) ? json.postuploadisoresponse.getuploadparams : ''
            const response = this.handleUpload()
            if (this.userdataid !== null) {
              this.linkUserdataToTemplate(this.userdataid, json.postuploadisoresponse.iso[0].id)
            }
            if (response === 'upload successful') {
              this.$notification.success({
                message: this.$t('message.success.upload'),
                description: this.$t('message.success.upload.iso.description')
              })
            }
          }).catch(error => {
            this.$notifyError(error)
          }).finally(() => {
            this.loading = false
            this.$emit('refresh-data')
          })
        }
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    },
    closeAction () {
      this.$emit('close-action')
    },
    linkUserdataToTemplate (userdataid, templateid, userdatapolicy) {
      this.loading = true
      const params = {}
      params.userdataid = userdataid
      params.templateid = templateid
      if (userdatapolicy) {
        params.userdatapolicy = userdatapolicy
      }
      postAPI('linkUserDataToTemplate', params).then(json => {
        this.closeAction()
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    },
    fetchDomains () {
      const params = {}
      params.listAll = true
      params.showicon = true
      params.details = 'min'
      this.domainLoading = true
      getAPI('listDomains', params).then(json => {
        this.domains = json.listdomainsresponse.domain
      }).finally(() => {
        this.domainLoading = false
        this.handleDomainChange(null)
      })
    },
    handleDomainChange (domain) {
      this.domainid = domain
      this.form.account = null
      this.account = null
      if ('listAccounts' in this.$store.getters.apis) {
        this.fetchAccounts()
      }
    },
    fetchAccounts () {
      getAPI('listAccounts', {
        domainid: this.domainid
      }).then(response => {
        this.accounts = response.listaccountsresponse.account || []
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    handleAccountChange (acc) {
      if (acc) {
        this.account = acc.name
      } else {
        this.account = acc
      }
    }
  }
}
</script>

<style scoped lang="less">
  .form-layout {
    width: 80vw;

    @media (min-width: 700px) {
      width: 550px;
    }
  }
</style>
