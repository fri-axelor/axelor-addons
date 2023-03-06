/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2022 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.redmine.service.imports.projects;

import com.axelor.apps.base.db.AppRedmine;
import com.axelor.apps.base.db.Batch;
import com.axelor.apps.base.db.repo.AppRedmineRepository;
import com.axelor.apps.base.db.repo.BatchRepository;
import com.axelor.apps.redmine.service.common.RedmineCommonService;
import com.axelor.apps.redmine.service.common.RedmineErrorLogService;
import com.axelor.apps.redmine.service.imports.fetch.RedmineFetchDataService;
import com.axelor.apps.redmine.service.imports.projects.pojo.MethodParameters;
import com.axelor.exception.service.TraceBackService;
import com.google.inject.Inject;
import com.taskadapter.redmineapi.RedmineException;
import com.taskadapter.redmineapi.RedmineManager;
import com.taskadapter.redmineapi.bean.User;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedmineProjectServiceImpl implements RedmineProjectService {

  protected RedmineImportProjectService redmineImportProjectService;
  protected RedmineFetchDataService redmineFetchDataService;
  protected RedmineErrorLogService redmineErrorLogService;
  protected BatchRepository batchRepo;
  protected AppRedmineRepository appRedmineRepository;

  @Inject
  public RedmineProjectServiceImpl(
      RedmineImportProjectService redmineImportProjectService,
      RedmineFetchDataService redmineFetchDataService,
      RedmineErrorLogService redmineErrorLogService,
      BatchRepository batchRepo,
      AppRedmineRepository appRedmineRepository) {

    this.redmineImportProjectService = redmineImportProjectService;
    this.redmineFetchDataService = redmineFetchDataService;
    this.redmineErrorLogService = redmineErrorLogService;
    this.batchRepo = batchRepo;
    this.appRedmineRepository = appRedmineRepository;
  }

  Logger LOG = LoggerFactory.getLogger(getClass());

  @Override
  public void redmineImportProject(
      Batch batch,
      RedmineManager redmineManager,
      Consumer<Object> onSuccess,
      Consumer<Throwable> onError) {

    RedmineCommonService.setResult("");

    AppRedmine appRedmine = appRedmineRepository.all().fetchOne();

    // LOGGER FOR REDMINE IMPORT ERROR DATA

    List<Object[]> errorObjList = new ArrayList<Object[]>();

    // FETCH IMPORT DATA

    Batch lastBatch =
        batchRepo
            .all()
            .filter(
                "self.id != ?1 and self.redmineBatch.id = ?2 and self.endDate != null",
                batch.getId(),
                batch.getRedmineBatch().getId())
            .order("-updatedOn")
            .fetchOne();

    LocalDateTime lastBatchUpdatedOn = lastBatch != null ? lastBatch.getUpdatedOn() : null;

    List<com.taskadapter.redmineapi.bean.Project> importProjectList = null;
    HashMap<Integer, String> redmineUserMap = new HashMap<Integer, String>();

    LOG.debug("Fetching projects from redmine..");

    try {
      importProjectList = redmineFetchDataService.fetchProjectImportData(redmineManager);

      List<User> redmineUserList = new ArrayList<>();

      Map<String, String> params = new HashMap<String, String>();
      // fetches only the active users
      params.put("status", appRedmine.getRedmineUsersStatus());
      // fetches only users from axelor
      // params.put("name", "%@axelor.com");
      if (!appRedmine.getOnUsersFilter().isEmpty()) {
        params.put("name", appRedmine.getOnUsersFilter());
      }

      Map<Integer, Boolean> includedIdsMap = new HashMap<>();
      LOG.debug("Fetching Axelor users from Redmine...");
      fillUsersList(redmineManager, includedIdsMap, redmineUserList, params);

      for (User user : redmineUserList) {
        redmineUserMap.put(user.getId(), user.getMail());
      }
    } catch (RedmineException e) {
      TraceBackService.trace(e, "", batch.getId());
    }

    // CREATE POJO FOR PASS TO THE METHODS

    MethodParameters methodParameters =
        new MethodParameters(
            onError,
            onSuccess,
            batch,
            errorObjList,
            lastBatchUpdatedOn,
            redmineUserMap,
            redmineManager);

    // IMPORT PROCESS

    redmineImportProjectService.importProject(importProjectList, methodParameters);
  }

  protected void fillUsersList(
      RedmineManager redmineManager,
      Map<Integer, Boolean> includedIdsMap,
      List<User> redmineUserList,
      Map<String, String> params)
      throws RedmineException {
    int offset = 0;
    int limit = 25;
    List<User> users = new ArrayList<>();
    while (users.size() < limit) {
      params.put("offset", String.valueOf(offset));
      params.put("limit", String.valueOf(limit));

      users = redmineManager.getUserManager().getUsers(params).getResults();

      for (User user : users) {
        if (!includedIdsMap.containsKey(user.getId())) {
          redmineUserList.add(user);
          includedIdsMap.put(user.getId(), true);
        }
      }
      offset += limit;
    }
  }
}
