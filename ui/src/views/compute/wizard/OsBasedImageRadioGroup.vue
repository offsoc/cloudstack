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
  <a-list
    class="form-item-scroll"
    itemLayout="vertical"
    size="small"
    :dataSource="imagesList"
    :pagination="false">
    <template #renderItem="{ item, index }">
      <a-list-item :key="item.id" @click="onClickRow(item)">
        <a-radio-group
          class="radio-group"
          :key="index"
          v-model:value="value"
          @change="($event) => updateSelectionTemplateIso($event.target.value)">
          <a-radio
            class="radio-group__radio"
            :value="item.id">
            <resource-icon
              v-if="item.icon && item.icon.base64image"
              class="radio-group__os-logo"
              :image="item.icon.base64image"
              size="2x" />
            <os-logo
              v-else
              class="radio-group__os-logo"
              size="2x"
              :osId="item.ostypeid"
              :os-name="item.osName" />
            &nbsp;
            {{ item.displaytext }}
            <span v-if="item?.projectid">
            | <project-outlined /> {{ item.project }}
            </span>
            <a-tooltip :title="$t('label.passwordenabled')" v-if="item.passwordenabled">
              <lock-outlined style="margin-left: 8px;"/>
            </a-tooltip>
            <a-tooltip :title="$t('label.userdata')" v-if="item.userdataid">
              <solution-outlined style="margin-left: 8px;"/>
            </a-tooltip>
            <a-tooltip :title="$t('label.isdynamicallyscalable')" v-if="item.isdynamicallyscalable">
              <arrows-alt-outlined style="margin-left: 8px;"/>
            </a-tooltip>
            <a-tooltip :title="$t('label.isextractable')" v-if="item.isextractable">
              <file-zip-outlined style="margin-left: 8px;"/>
            </a-tooltip>
            <a-tag v-if="item.isfeatured" style="margin-left: 8px;">
              {{ $t('label.isfeatured') }}
            </a-tag>
            <a-tag v-if="item.ispublic" style="margin-left: 8px;">
              {{ $t('label.ispublic') }}
            </a-tag>
            <a-tag v-if="item.directdownload" style="margin-left: 8px;">
              {{ $t('label.directdownload') }}
            </a-tag>
            <a-tag v-if="item.requireshvm" style="margin-left: 8px;">
              {{ $t('label.requireshvm') }}
            </a-tag>
          </a-radio>
        </a-radio-group>
      </a-list-item>
    </template>
  </a-list>

  <div style="display: block; text-align: right;">
    <a-pagination
      size="small"
      :current="options.page"
      :pageSize="options.pageSize"
      :total="itemCount"
      :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
      :pageSizeOptions="['10', '20', '40', '80', '100', '200']"
      @change="onChangePage"
      @showSizeChange="onChangePageSize"
      showSizeChanger>
      <template #buildOptionText="props">
        <span>{{ props.value }} / {{ $t('label.page') }}</span>
      </template>
    </a-pagination>
  </div>
</template>

<script>
import OsLogo from '@/components/widgets/OsLogo'
import ResourceIcon from '@/components/view/ResourceIcon'

export default {
  name: 'OsBasedImageRadioGroup',
  components: {
    OsLogo,
    ResourceIcon
  },
  props: {
    imagesList: {
      type: Array,
      default: () => []
    },
    inputDecorator: {
      type: String,
      default: ''
    },
    selected: {
      type: String,
      default: ''
    },
    itemCount: {
      type: Number,
      default: 0
    },
    preFillContent: {
      type: Object,
      default: () => {}
    }
  },
  data () {
    return {
      value: '',
      image: '',
      options: {
        page: 1,
        pageSize: 10
      }
    }
  },
  mounted () {
    this.onSelectTemplateIso()
  },
  watch: {
    selected (newVal, oldVal) {
      if (newVal === oldVal) return
      this.onSelectTemplateIso()
    },
    imagesList () {
      if (this.value === '' && this.imagesList && this.imagesList.length > 0) {
        this.onClickRow(this.imagesList[0])
      }
    }
  },
  emits: ['emit-update-image', 'handle-search-filter'],
  methods: {
    onSelectTemplateIso () {
      if (this.inputDecorator === 'templateid') {
        this.value = this.preFillContent?.templateid ? this.preFillContent.templateid : this.selected
      } else {
        this.value = this.preFillContent?.isoid ? this.preFillContent.isoid : this.selected
      }

      this.$emit('emit-update-image', this.inputDecorator, this.value)
    },
    updateSelectionTemplateIso (id) {
      this.$emit('emit-update-image', this.inputDecorator, id)
    },
    onChangePage (page, pageSize) {
      this.options.page = page
      this.options.pageSize = pageSize
      this.$emit('handle-search-filter', this.options)
    },
    onChangePageSize (page, pageSize) {
      this.options.page = page
      this.options.pageSize = pageSize
      this.$emit('handle-search-filter', this.options)
    },
    onClickRow (os) {
      this.value = os.id
      this.$emit('emit-update-image', this.inputDecorator, this.value)
    }
  }
}
</script>

<style lang="less" scoped>
  .radio-group {
    margin: 0.5rem 0;

    :deep(.ant-radio) {
      margin-right: 0px;
    }

    &__os-logo {
      margin-top: -4px;
    }
  }

  :deep(.ant-spin-container) {
    max-height: 200px;
    overflow-y: auto;
  }

  .pagination {
    margin-top: 20px;
    float: right;
  }

  :deep(.ant-list-split) .ant-list-item {
    cursor: pointer;
  }
</style>
