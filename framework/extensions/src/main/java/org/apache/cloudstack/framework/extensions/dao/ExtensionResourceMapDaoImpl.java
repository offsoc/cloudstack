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

package org.apache.cloudstack.framework.extensions.dao;

import org.apache.cloudstack.extension.ExtensionResourceMap;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import org.apache.cloudstack.framework.extensions.vo.ExtensionResourceMapVO;

import java.util.List;

public class ExtensionResourceMapDaoImpl extends GenericDaoBase<ExtensionResourceMapVO, Long> implements ExtensionResourceMapDao {
    private final SearchBuilder<ExtensionResourceMapVO> genericSearch;

    public ExtensionResourceMapDaoImpl() {
        super();

        genericSearch = createSearchBuilder();
        genericSearch.and("extensionId", genericSearch.entity().getExtensionId(), SearchCriteria.Op.EQ);
        genericSearch.and("resourceId", genericSearch.entity().getResourceId(), SearchCriteria.Op.EQ);
        genericSearch.and("resourceType", genericSearch.entity().getResourceType(), SearchCriteria.Op.EQ);
        genericSearch.done();
    }

    @Override
    public List<ExtensionResourceMapVO> listByExtensionId(long extensionId) {
        SearchCriteria<ExtensionResourceMapVO> sc = genericSearch.create();
        sc.setParameters("extensionId", extensionId);
        return listBy(sc);
    }

    @Override
    public ExtensionResourceMapVO findByResourceIdAndType(long resourceId,
              ExtensionResourceMap.ResourceType resourceType) {
        SearchCriteria<ExtensionResourceMapVO> sc = genericSearch.create();
        sc.setParameters("resourceId", resourceId);
        sc.setParameters("resourceType", resourceType);
        return findOneBy(sc);
    }

    @Override
    public List<Long> listResourceIdsByExtensionIdAndType(long extensionId, ExtensionResourceMap.ResourceType resourceType) {
        GenericSearchBuilder<ExtensionResourceMapVO, Long> sb = createSearchBuilder(Long.class);
        sb.selectFields(sb.entity().getResourceId());
        sb.and("extensionId", sb.entity().getExtensionId(), SearchCriteria.Op.EQ);
        sb.and("resourceType", sb.entity().getResourceType(), SearchCriteria.Op.EQ);
        sb.done();
        SearchCriteria<Long> sc = sb.create();
        sc.setParameters("extensionId", extensionId);
        sc.setParameters("resourceType", resourceType);
        return customSearch(sc, null);
    }
}
