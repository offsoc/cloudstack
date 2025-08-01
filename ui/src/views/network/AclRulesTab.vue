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
  <a-spin :spinning="fetchLoading">

     <div style="width: 100%; display: flex">
      <a-button
        type="dashed"
        style="width: 100%; margin-right: 10px"
        :disabled="!('createNetworkACL' in $store.getters.apis)"
        @click="openAddRuleModal">
        <template #icon><plus-outlined /></template>
        {{ $t('label.add.acl.rule') }}
      </a-button>

      <a-button type="dashed" @click="exportAclList" style="width: 100%">
        <template #icon><download-outlined /></template>
        {{ $t('label.acl.export') }}
      </a-button>
      <div class="search-bar">
        <a-input-search
          style="width: 25vw;float: right;margin-left: 10px; z-index: 8"
          :placeholder="$t('label.search')"
          v-model:value="searchQuery"
          @search="fetchData"
        />
      </div>
    </div>

    <div class="list">
      <draggable
        v-model="acls"
        @change="changeOrder"
        handle=".drag-handle"
        animation="200"
        ghostClass="drag-ghost"
        :component-data="{type: 'transition'}"
        item-key="id">
        <template #item="{element}">
          <div class="list__item">
            <div class="drag-handle">
              <drag-outlined />
            </div>
            <div class="list__container">
              <div class="list__col">
                <div class="list__label">{{ $t('label.rule.number') }}</div>
                <div>{{ element.number }}</div>
              </div>
              <div class="list__col">
                <div class="list__label">{{ $t('label.cidrlist') }}</div>
                <div>{{ element.cidrlist }}</div>
              </div>
              <div class="list__col">
                <div class="list__label">{{ $t('label.action') }}</div>
                <div>{{ element.action }}</div>
              </div>
              <div class="list__col">
                <div class="list__label">{{ $t('label.protocol') }}</div>
                <div>{{ element.protocol }}</div>
              </div>
              <div class="list__col" v-if="element.startport !== undefined">
                <div class="list__label">{{ $t('label.startport') }}</div>
                <div>{{ element.startport }}</div>
              </div>
              <div class="list__col" v-if="element.endport !== undefined">
                <div class="list__label">{{ $t('label.endport') }}</div>
                <div>{{ element.endport }}</div>
              </div>
              <div class="list__col" v-if="element.icmpcode !== undefined">
                <div class="list__label">{{ $t('label.icmpcode') }}</div>
                <div>{{ element.icmpcode }}</div>
              </div>
              <div class="list__col" v-if="element.icmptype !== undefined">
                <div class="list__label">{{ $t('label.icmptype') }}</div>
                <div>{{ element.icmptype }}</div>
              </div>
              <div class="list__col">
                <div class="list__label">{{ $t('label.traffictype') }}</div>
                <div>{{ element.traffictype }}</div>
              </div>
              <div class="list__col">
                <div class="list__label">{{ $t('label.description') }}</div>
                <div>{{ element.reason }}</div>
              </div>
            </div>
            <div class="list__actions">
              <tooltip-button
                v-if="element.id !== acls[0].id"
                :tooltip="$t('label.move.to.top')"
                icon="vertical-align-top-outlined"
                @onClick="() => moveRuleToTop(element)" />
              <tooltip-button
                v-if="element.id !== acls[acls.length - 1].id"
                :tooltip="$t('label.move.to.bottom')"
                icon="vertical-align-bottom-outlined"
                @onClick="() => moveRuleToBottom(element)" />
              <tooltip-button :tooltip="$t('label.tags')" icon="tag-outlined" @onClick="() => openTagsModal(element)" />
              <tooltip-button :tooltip="$t('label.edit')" icon="edit-outlined" @onClick="() => openEditRuleModal(element)" />
              <tooltip-button
                :tooltip="$t('label.delete')"
                icon="delete-outlined"
                type="primary"
                :danger="true"
                :disabled="!('deleteNetworkACL' in $store.getters.apis)"
                @onClick="() => handleDeleteRule(element.id)" />
            </div>
          </div>
        </template>
      </draggable>
    </div>

    <a-modal
      v-if="tagsModalVisible"
      :title="$t('label.edit.tags')"
      :visible="tagsModalVisible"
      :footer="null"
      :closable="true"
      :maskClosable="false"
      @cancel="tagsModalVisible = false">
      <a-spin v-if="tagsLoading"></a-spin>

      <div v-else>
        <a-form
          :ref="formRef"
          :model="form"
          :rules="rules"
          class="add-tags"
          v-ctrl-enter="handleAddTag"
         >
          <div class="add-tags__input">
            <p class="add-tags__label">{{ $t('label.key') }}</p>
            <a-form-item ref="key" name="key">
              <a-input
                v-focus="true"
                v-model:value="form.key" />
            </a-form-item>
          </div>
          <div class="add-tags__input">
            <p class="add-tags__label">{{ $t('label.value') }}</p>
            <a-form-item  ref="value" name="value">
              <a-input v-model:value="form.value" />
            </a-form-item>
          </div>
          <a-button ref="submit" type="primary" :disabled="!(form.key && form.value)" @click="handleAddTag">{{ $t('label.add') }}</a-button>
        </a-form>

        <a-divider style="margin-top: 0;" />

        <div class="tags-container">
          <div class="tags" v-for="(tag, index) in tags" :key="index">
            <a-tag :key="index" :closable="true" @close="() => handleDeleteTag(tag)">
              {{ tag.key }} = {{ tag.value }}
            </a-tag>
          </div>
        </div>

        <a-button class="add-tags-done" @click="tagsModalVisible = false" type="primary">{{ $t('label.ok') }}</a-button>
      </div>

    </a-modal>
    <a-modal
      v-if="ruleModalVisible"
      :title="ruleModalTitle"
      :closable="true"
      :maskClosable="false"
      :footer="null"
      :visible="ruleModalVisible"
      @cancel="ruleModalVisible = false">
      <a-form
        :ref="formRef"
        :model="form"
        :rules="rules"
        @finish="handleRuleModalForm"
        v-ctrl-enter="handleRuleModalForm"
       >
        <a-form-item :label="$t('label.number')" ref="number" name="number">
          <a-input-number v-focus="true" style="width: 100%" v-model:value="form.number" />
        </a-form-item>
        <a-form-item :label="$t('label.cidrlist')" ref="cidrlist" name="cidrlist">
          <a-input v-model:value="form.cidrlist" />
        </a-form-item>
        <a-form-item :label="$t('label.action')" ref="action" name="action">
          <a-select
            v-model:value="form.action"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option value="allow" :label="$t('label.allow')">{{ $t('label.allow') }}</a-select-option>
            <a-select-option value="deny" :label="$t('label.deny')">{{ $t('label.deny') }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item :label="$t('label.protocol')" ref="protocol" name="protocol">
          <a-select
           v-model:value="form.protocol"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option value="tcp" :label="$t('label.tcp')">{{ capitalise($t('label.tcp')) }}</a-select-option>
            <a-select-option value="udp" :label="$t('label.udp')">{{ capitalise($t('label.udp')) }}</a-select-option>
            <a-select-option value="icmp" :label="$t('label.icmp')">{{ capitalise($t('label.icmp')) }}</a-select-option>
            <a-select-option value="all" :label="$t('label.all')">{{ $t('label.all') }}</a-select-option>
            <a-select-option value="protocolnumber" :label="$t('label.protocol.number')">{{ $t('label.protocol.number') }}</a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item
          v-if="form.protocol === 'protocolnumber'"
          :label="$t('label.protocolnumber')"
          ref="protocolnumber"
          name="protocolnumber">
          <a-select
            v-model:value="form.protocolnumber"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option v-for="(opt, optIndex) in protocolNumbers" :key="optIndex" :label="opt.name">
              {{ opt.index + ' - ' + opt.name }}
            </a-select-option>
          </a-select>
        </a-form-item>

        <div v-if="form.protocol === 'icmp' || (form.protocol === 'protocolnumber' && form.protocolnumber === 1)">
          <a-form-item :label="$t('label.icmptype')" ref="icmptype" name="icmptype">
            <a-select
              v-model:value="form.icmptype"
              @change="val => { updateIcmpCodes(val) }"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
              return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
              <a-select-option v-for="(opt) in icmpTypes" :key="opt.index" :label="opt.description">
                {{ opt.index + ' - ' + opt.description }}
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item :label="$t('label.icmpcode')" ref="icmpcode" name="icmpcode">
            <a-select
              v-model:value="form.icmpcode"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
              return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
              <a-select-option v-for="(opt) in icmpCodes" :key="opt.code" :label="opt.description">
                {{ opt.code + ' - ' + opt.description }}
              </a-select-option>
            </a-select>
          </a-form-item>
        </div>

        <div v-show="['tcp', 'udp', 'protocolnumber'].includes(form.protocol) && !(form.protocol === 'protocolnumber' && form.protocolnumber === 1)">
          <a-form-item :label="$t('label.startport')" ref="startport" name="startport">
            <a-input-number style="width: 100%" v-model:value="form.startport" />
          </a-form-item>
          <a-form-item :label="$t('label.endport')" ref="endport" name="endport">
            <a-input-number style="width: 100%" v-model:value="form.endport" />
          </a-form-item>
        </div>

        <a-form-item :label="$t('label.traffictype')" ref="traffictype" name="traffictype">
          <a-select
            v-model:value="form.traffictype"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option value="ingress" :label="$t('label.ingress')">{{ $t('label.ingress') }}</a-select-option>
            <a-select-option value="egress" :label="$t('label.egress')">{{ $t('label.egress') }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item :label="$t('label.description')" ref="reason" name="reason">
          <a-textarea
            v-model:value="form.reason"
            :autoSize="{ minRows: 2 }"
            :placeholder="$t('label.acl.reason.description')" />
        </a-form-item>

        <div :span="24" class="action-button">
          <a-button @click="() => { ruleModalVisible = false } ">{{ $t('label.cancel') }}</a-button>
          <a-button ref="submit" type="primary" @click="handleRuleModalForm">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-modal>
  </a-spin>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import draggable from 'vuedraggable'
import { mixinForm } from '@/utils/mixin'
import TooltipButton from '@/components/widgets/TooltipButton'

export default {
  name: 'AclListRulesTab',
  mixins: [mixinForm],
  components: {
    draggable,
    TooltipButton
  },
  props: {
    resource: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return {
      searchQuery: '', // Bind this to the search input
      acls: [],
      fetchLoading: false,
      protocolNumbers: [],
      icmpTypes: [],
      icmpCodes: [],
      tags: [],
      selectedAcl: null,
      tagsModalVisible: false,
      tagsLoading: false,
      ruleModalVisible: false,
      ruleModalTitle: this.$t('label.edit.rule'),
      ruleFormMode: 'edit'
    }
  },
  created () {
    this.initForm()
    this.fetchNetworkProtocols()
    this.fetchData()
  },
  watch: {
    loading (newData, oldData) {
      if (!newData && this.resource.id) {
        this.fetchData()
      }
    }
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({})
      this.form.icmptype = -1
      this.form.icmpcode = -1
    },
    csv ({ data = null, columnDelimiter = ',', lineDelimiter = '\n' }) {
      let result = null
      let ctr = null
      let keys = null

      if (data === null || !data.length) {
        return null
      }

      keys = Object.keys(data[0])
      for (var i = 1; i < data.length; ++i) {
        const rowKeys = Object.keys(data[i])
        keys = keys.concat(rowKeys.filter(k => !keys.includes(k)))
      }

      result = ''
      result += keys.join(columnDelimiter)
      result += lineDelimiter

      data.forEach(item => {
        ctr = 0
        keys.forEach(key => {
          if (ctr > 0) {
            result += columnDelimiter
          }

          if (key === 'tags') {
            var tags = '"'
            if (item[key].length > 0) {
              item[key].forEach(tag => {
                tags += '(' + tag.key + ',' + tag.value + ')'
              })
            }
            result += tags + '"'
          } else {
            result += typeof item[key] === 'string' && item[key].includes(columnDelimiter) ? `"${item[key]}"` : item[key]
          }
          ctr++
        })
        result += lineDelimiter
      })

      return result
    },
    fetchNetworkProtocols () {
      getAPI('listNetworkProtocols', {
        option: 'protocolnumber'
      }).then(json => {
        this.protocolNumbers = json.listnetworkprotocolsresponse?.networkprotocol || []
      })
      getAPI('listNetworkProtocols', {
        option: 'icmptype'
      }).then(json => {
        this.icmpTypes.push({ index: -1, description: this.$t('label.all') })
        const results = json.listnetworkprotocolsresponse?.networkprotocol || []
        for (const result of results) {
          this.icmpTypes.push(result)
        }
      })
      this.icmpCodes.push({ code: -1, description: this.$t('label.all') })
    },
    updateIcmpCodes (val) {
      this.form.icmpcode = -1
      this.icmpCodes = []
      this.icmpCodes.push({ code: -1, description: this.$t('label.all') })
      const icmpType = this.icmpTypes.find(icmpType => icmpType.index === val)
      if (icmpType && icmpType.details) {
        const icmpTypeDetails = icmpType.details
        for (const k of Object.keys(icmpTypeDetails)) {
          this.icmpCodes.push({ code: parseInt(k), description: icmpTypeDetails[k] })
        }
      }
    },
    fetchData () {
      this.fetchLoading = true
      const params = {
        aclid: this.resource.id,
        keyword: this.searchQuery
      }
      getAPI('listNetworkACLs', params).then(json => {
        this.acls = json.listnetworkaclsresponse.networkacl || []
        if (this.acls.length > 0) {
          this.acls.sort((a, b) => a.number - b.number)
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.fetchLoading = false
      })
    },
    fetchTags (acl) {
      getAPI('listTags', {
        resourceId: acl.id,
        resourceType: 'NetworkACL',
        listAll: true
      }).then(response => {
        this.tags = response.listtagsresponse.tag || []
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    openTagsModal (acl) {
      this.initForm()
      this.rules = {
        key: [{ required: true, message: this.$t('message.specify.tag.key') }],
        value: [{ required: true, message: this.$t('message.specify.tag.value') }]
      }
      this.selectedAcl = acl
      this.fetchTags(this.selectedAcl)
      this.tagsModalVisible = true
    },
    handleDeleteTag (tag) {
      this.tagsLoading = true
      postAPI('deleteTags', {
        'tags[0].key': tag.key,
        'tags[0].value': tag.value,
        resourceIds: this.selectedAcl.id,
        resourceType: 'NetworkACL'
      }).then(response => {
        this.$pollJob({
          jobId: response.deletetagsresponse.jobid,
          title: this.$t('message.delete.tag.for.networkacl'),
          description: `${tag.key} = ${tag.value}`,
          successMessage: this.$t('message.success.delete.tag'),
          successMethod: () => {
            this.fetchTags(this.selectedAcl)
            this.tagsLoading = false
          },
          errorMessage: this.$t('message.delete.tag.failed'),
          errorMethod: () => {
            this.fetchTags(this.selectedAcl)
            this.tagsLoading = false
          },
          loadingMessage: this.$t('message.delete.tag.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.fetchTags(this.selectedAcl)
            this.tagsLoading = false
          }
        })
      }).catch(error => {
        this.$notifyError(error)
        this.tagsLoading = false
      })
    },
    handleAddTag () {
      if (this.tagsLoading) return
      this.tagsLoading = true

      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)

        postAPI('createTags', {
          'tags[0].key': values.key,
          'tags[0].value': values.value,
          resourceIds: this.selectedAcl.id,
          resourceType: 'NetworkACL'
        }).then(response => {
          this.$pollJob({
            jobId: response.createtagsresponse.jobid,
            title: this.$t('message.add.tag.for.networkacl'),
            description: `${values.key} = ${values.value}`,
            successMessage: this.$t('message.success.add.tag'),
            successMethod: () => {
              this.fetchTags(this.selectedAcl)
              this.tagsLoading = false
            },
            errorMessage: this.$t('message.add.tag.failed'),
            errorMethod: () => {
              this.fetchTags(this.selectedAcl)
              this.tagsLoading = false
            },
            loadingMessage: this.$t('message.add.tag.processing'),
            catchMessage: this.$t('error.fetching.async.job.result'),
            catchMethod: () => {
              this.fetchTags(this.selectedAcl)
              this.tagsLoading = false
            }
          })
        }).catch(error => {
          this.$notifyError(error)
          this.tagsLoading = false
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      }).finally(() => {
        this.tagsLoading = false
      })
    },
    openEditRuleModal (acl) {
      const self = this
      this.initForm()
      this.rules = { protocolnumber: [{ required: true, message: this.$t('label.required') }] }
      this.ruleModalTitle = this.$t('label.edit.rule')
      this.ruleFormMode = 'edit'
      this.ruleModalVisible = true
      this.selectedAcl = acl
      setTimeout(() => {
        self.form.number = acl.number
        self.form.cidrlist = acl.cidrlist
        self.form.action = acl.action
        self.form.protocol = acl.protocol
        if (!['tcp', 'udp', 'icmp', 'all'].includes(acl.protocol)) {
          self.form.protocol = 'protocolnumber'
          self.form.protocolnumber = parseInt(acl.protocol)
        }
        self.form.startport = acl.startport
        self.form.endport = acl.endport
        self.form.icmptype = parseInt(acl.icmptype)
        this.updateIcmpCodes(self.form.icmptype)
        self.form.icmpcode = acl.icmpcode
        self.form.traffictype = acl.traffictype
        self.form.reason = acl.reason
      }, 200)
    },
    getDataFromForm (values) {
      const data = {
        cidrlist: values.cidrlist || '',
        number: values.number || '',
        protocol: values.protocol || '',
        traffictype: values.traffictype || '',
        action: values.action || '',
        reason: values.reason || ''
      }

      if (values.protocol === 'tcp' || values.protocol === 'udp' || values.protocol === 'protocolnumber') {
        data.startport = values.startport || ''
        data.endport = values.endport || ''
      }

      if (values.protocol === 'icmp' || (values.protocol === 'protocolnumber' && values.protocolnumber === 1)) {
        data.icmptype = values.icmptype
        data.icmpcode = values.icmpcode
      }

      if (values.protocol === 'protocolnumber') {
        data.protocol = values.protocolnumber
      }

      return data
    },
    handleEditRule () {
      this.formRef.value.validate().then(() => {
        const formRaw = toRaw(this.form)
        const values = this.handleRemoveFields(formRaw)
        this.fetchLoading = true
        this.ruleModalVisible = false

        const data = this.getDataFromForm(values)
        data.id = this.selectedAcl.id
        data.partialupgrade = false

        postAPI('updateNetworkACLItem', data).then(response => {
          this.$pollJob({
            jobId: response.createnetworkaclresponse.jobid,
            title: this.$t('label.edit.acl.rule'),
            description: this.selectedAcl.id,
            successMessage: this.$t('message.success.edit.acl'),
            successMethod: () => {
              this.fetchData()
              this.fetchLoading = false
            },
            errorMessage: this.$t('message.edit.acl.failed'),
            errorMethod: () => {
              this.fetchData()
              this.fetchLoading = false
            },
            loadingMessage: this.$t('message.edit.acl.processing'),
            catchMessage: this.$t('error.fetching.async.job.result'),
            catchMethod: () => {
              this.fetchData()
              this.fetchLoading = false
            }
          })
        }).catch(error => {
          this.$notifyError(error)
          this.fetchLoading = false
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    },
    handleDeleteRule (id) {
      this.fetchLoading = true
      postAPI('deleteNetworkACL', { id }).then(response => {
        this.$pollJob({
          jobId: response.deletenetworkaclresponse.jobid,
          title: this.$t('message.delete.acl.rule'),
          description: id,
          successMessage: this.$t('message.success.delete.acl.rule'),
          successMethod: () => {
            this.fetchData()
            this.fetchLoading = false
          },
          errorMessage: this.$t('message.delete.acl.rule.failed'),
          errorMethod: () => {
            this.fetchData()
            this.fetchLoading = false
          },
          loadingMessage: this.$t('message.delete.acl.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.fetchData()
            this.fetchLoading = false
          }
        })
      }).catch(error => {
        this.$notifyError(error)
        this.fetchLoading = false
      })
    },
    handleRuleModalForm (e) {
      if (this.fetchLoading) return
      if (this.ruleFormMode === 'edit') {
        this.handleEditRule(e)
        return
      }
      if (this.ruleFormMode === 'add') this.handleAddRule(e)
    },
    openAddRuleModal () {
      this.ruleModalTitle = this.$t('label.add.rule')
      this.ruleModalVisible = true
      this.ruleFormMode = 'add'
      this.initForm()
      this.rules = { protocolnumber: [{ required: true, message: this.$t('label.required') }] }
      setTimeout(() => {
        this.form.action = 'allow'
        this.form.protocol = 'tcp'
        this.form.traffictype = 'ingress'
      }, 200)
    },
    handleAddRule (e) {
      this.formRef.value.validate().then(() => {
        const formRaw = toRaw(this.form)
        const values = this.handleRemoveFields(formRaw)
        this.fetchLoading = true
        this.ruleModalVisible = false

        const data = this.getDataFromForm(values)
        data.aclid = this.resource.id

        postAPI('createNetworkACL', data).then(() => {
          this.$notification.success({
            message: this.$t('label.success'),
            description: this.$t('message.success.add.rule')
          })
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.fetchLoading = false
          this.fetchData()
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    },
    changeOrder (e) {
      const id = e.moved.element.id
      let previousaclruleid = null
      let nextaclruleid = null

      if (e.moved.newIndex - 1 >= 0) previousaclruleid = this.acls[e.moved.newIndex - 1].id

      if (e.moved.newIndex + 1 < this.acls.length) nextaclruleid = this.acls[e.moved.newIndex + 1].id

      this.moveRule(
        id,
        previousaclruleid,
        nextaclruleid)
    },
    moveRule (id, previousaclruleid, nextaclruleid) {
      this.fetchLoading = true
      postAPI('moveNetworkAclItem', {
        id,
        previousaclruleid,
        nextaclruleid
      }).then(response => {
        this.$pollJob({
          jobId: response.moveNetworkAclItemResponse.jobid,
          title: this.$t('message.move.acl.order'),
          description: id,
          successMessage: this.$t('message.success.move.acl.order'),
          successMethod: () => {
            this.fetchData()
            this.fetchLoading = false
          },
          errorMessage: this.$t('message.move.acl.order.failed'),
          errorMethod: () => {
            this.fetchData()
            this.fetchLoading = false
          },
          loadingMessage: this.$t('message.move.acl.order.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.fetchData()
            this.fetchLoading = false
          }
        })
      }).catch(error => {
        this.$notifyError(error)
        this.fetchLoading = false
      })
    },
    moveRuleToTop (element) {
      this.moveRule(element.id, null, this.acls[0].id)
    },
    moveRuleToBottom (element) {
      this.moveRule(element.id, this.acls[this.acls.length - 1].id, null)
    },
    exportAclList () {
      const csvData = this.csv({ data: this.acls })

      const hiddenElement = document.createElement('a')
      hiddenElement.href = 'data:text/csv;charset=utf-8,' + encodeURIComponent(csvData)
      hiddenElement.target = '_blank'
      hiddenElement.download = 'AclRules-' + this.resource.name + '-' + this.resource.id + '.csv'
      hiddenElement.click()
    },
    capitalise (val) {
      return val.toUpperCase()
    }
  }
}
</script>

<style lang="scss" scoped>
.list {

  &__item {
    display: flex;
    padding-top: 20px;
    padding-bottom: 20px;

    &:not(:last-child) {
      border-bottom: 1px solid #d9d9d9;
    }

  }

  &__container {
    display: flex;
    flex-wrap: wrap;
    width: 100%;
  }

  &__col {
    margin-right: 20px;
    margin-bottom: 20px;
    flex-basis: calc(50% - 20px);

    @media (min-width: 760px) {
      flex-basis: calc(25% - 20px);
    }

  }

  &__label {
    font-weight: bold;
  }

  &__actions {
    display: flex;
    flex-direction: column;

    button {
      &:not(:last-child) {
        margin-bottom: 10px;
      }
    }

  }

}

.tags {
  margin-bottom: 10px;
}
.add-tags {
  display: flex;
  align-items: center;
  justify-content: space-between;
  &__input {
    margin-right: 10px;
  }
  &__label {
    margin-bottom: 5px;
    font-weight: bold;
  }
}
.tags-container {
  display: flex;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.add-tags-done {
  display: block;
  margin-left: auto;
}

.drag-handle {
  display: flex;
  align-items: center;
  cursor: pointer;
  padding-right: 20px;
}

.drag-ghost {
  opacity: 0.5;
  background: #f0f2f5;
}

.download {
  display: block;
  margin-top: 10px;
  margin-bottom: 10px;
  margin-left: auto;
}
</style>
