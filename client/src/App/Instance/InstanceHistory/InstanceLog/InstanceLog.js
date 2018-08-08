import React from 'react';
import PropTypes from 'prop-types';

import {getWorkflowName} from 'modules/utils/instance';
import {HEADER} from 'modules/constants';

import * as Styled from './styled';

export default class InstanceLog extends React.Component {
  static propTypes = {
    instance: PropTypes.object,
    activitiesDetails: PropTypes.shape({
      id: PropTypes.string,
      state: PropTypes.string,
      type: PropTypes.string,
      name: PropTypes.string
    })
  };

  state = {
    selected: HEADER
  };

  setSelected = selected => {
    this.setState({selected});
  };

  renderLogEntry = ([id, {state, type, name}]) => (
    <Styled.LogEntry
      key={id}
      isSelected={this.state.selected === id}
      onClick={() => this.setSelected(id)}
    >
      <Styled.FlowNodeIcon
        state={state}
        type={type}
        isSelected={this.state.selected === id}
      />
      {name}
    </Styled.LogEntry>
  );

  render() {
    const {instance, activitiesDetails} = this.props;

    return (
      <Styled.InstanceLog {...this.props}>
        <React.Fragment>
          <Styled.Header
            isSelected={this.state.selected === HEADER}
            onClick={() => this.setSelected(HEADER)}
          >
            <Styled.DocumentIcon isSelected={this.state.selected === HEADER} />
            {getWorkflowName(instance)}
          </Styled.Header>
          {activitiesDetails
            ? Object.entries(activitiesDetails).map(this.renderLogEntry)
            : null}
        </React.Fragment>
      </Styled.InstanceLog>
    );
  }
}
