/*

    Copyright (C) 2019 AGNITAS AG (https://www.agnitas.org)

    This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
    You should have received a copy of the GNU Affero General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.

*/

package com.agnitas.emm.springws.throttling.tokenbucket.dao;

import org.agnitas.dao.impl.BaseDaoImpl;
import org.apache.log4j.Logger;

import com.agnitas.emm.springws.throttling.tokenbucket.common.WebserviceInvocationCosts;

public final class DatabaseBasedWebserviceInvocationCosts extends BaseDaoImpl implements WebserviceInvocationCosts {

	/** The logger. */
	private static final transient Logger LOGGER = Logger.getLogger(DatabaseBasedWebserviceInvocationCosts.class);
	
	public static final int DEFAULT_INVOCATION_COSTS = 1;
	
	@Override
	public final int executionCostsForEndpoint(final int companyID, final String endpointName) {
		int costs = queryCosts(companyID, endpointName);
		
		if(costs == 0) {
			costs = queryCosts(0, endpointName);
		}
		
		if(costs == 0) {
			costs = DEFAULT_INVOCATION_COSTS;
		}
		
		return costs;
	}

	private final int queryCosts(final int companyID, final String endpointName) {
		final String sql = "SELECT costs FROM webservice_endpoint_cost_tbl WHERE company_ref=? and endpoint=?";

		return selectInt(LOGGER, sql, companyID, endpointName);
	}
	
}