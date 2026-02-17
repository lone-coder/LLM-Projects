# -*- coding: utf-8 -*-

import httpx
from openai import OpenAI
import json
import random
import math
import os
import subprocess
import numpy as np # Required for PyMC/JAX and prior generation
from scipy.stats import norm # For generating draws for R script priors
import re
import difflib

# --- Text Cleaning Functions ---
def clean_text_for_embeddings(text):
    """Clean text to make it more suitable for word embeddings."""
    if not text:
        return text
    
    # Remove parenthetical phrases
    text = re.sub(r'\s*\([^)]*\)', '', text)
    
    # Replace & with "and"
    text = re.sub(r'\s*&\s*', ' and ', text)
    
    # Clean up extra whitespace
    text = ' '.join(text.split())
    
    return text.strip()

def normalize_level_ordering(levels_list):
    """Ensure levels follow a consistent low->high or high->low pattern based on common ordering words."""
    if not levels_list or len(levels_list) != 3:
        return levels_list
    
    # Keywords that suggest ordering (low to high)
    low_indicators = ['low', 'lower', 'less', 'fewer', 'minimal', 'basic', 'poor', 'small', 'short', 'near', 'close']
    high_indicators = ['high', 'higher', 'more', 'greater', 'maximum', 'premium', 'excellent', 'large', 'long', 'far', 'distant']
    
    scored_levels = []
    for i, level in enumerate(levels_list):
        level_lower = level.lower()
        score = 0
        
        # Count low indicators (negative score)
        for indicator in low_indicators:
            if indicator in level_lower:
                score -= 1
        
        # Count high indicators (positive score)
        for indicator in high_indicators:
            if indicator in level_lower:
                score += 1
        
        scored_levels.append((score, i, level))
    
    # Sort by score (low to high)
    scored_levels.sort(key=lambda x: x[0])
    
    # Return reordered levels
    return [level for _, _, level in scored_levels]

# --- LLM Call ---
def llm_call(system_prompt, user_prompt, model_name, temperature=0.5):
    """
    Calls the specified local LLM and returns its raw text response.
    """
    base_url = "http://127.0.0.1:1234/v1"
    api_key = "not-needed"
    try:
        client = OpenAI(base_url=base_url, api_key=api_key, timeout=httpx.Timeout(360.0))
        messages = [{"role": "system", "content": system_prompt}, {"role": "user", "content": user_prompt}]

        print(f"\n--- Attempting LLM Call ---")
        print(f"Using Model: {model_name}")
        print(f"System Prompt (first 100 chars): {system_prompt[:100]}...")
        print(f"User Prompt (first 100 chars): {user_prompt[:100]}...")

        completion = client.chat.completions.create(
            model=model_name,
            messages=messages,
            temperature=temperature
        )
        response_text = completion.choices[0].message.content
        print(f"--- LLM Raw Text Response Received (from {model_name}) ---")
        return response_text

    except Exception as e:
        print(f"\n--- LLM Call Failed for model {model_name}: {e}. ---")
        return None

# --- User Profile Management (HBM) ---
class UserProfileHBM:
    """Manages the user's long-term preference profile."""
    def __init__(self, user_id="default_user"):
        self.user_id = user_id
        self.profile_file = f"user_profile_ubc_{self.user_id}.json"
        self.data = self._load()
    def _load(self):
        if os.path.exists(self.profile_file):
            try:
                with open(self.profile_file, 'r') as f: return json.load(f)
            except json.JSONDecodeError:
                print(f"Warning: Corrupted JSON in {self.profile_file}. Starting fresh.")
                return {}
        return {}
    def save(self):
        with open(self.profile_file, 'w') as f: json.dump(self.data, f, indent=4)
    def get_level_prior(self, criterion, level):
        default_mean, default_var = 0.0, 10.0
        if criterion in self.data and self.data[criterion] and level in self.data[criterion]:
            return self.data[criterion][level].get('mean_utility', default_mean), self.data[criterion][level].get('variance', default_var)
        return default_mean, default_var
    def update_profile_with_posteriors(self, posteriors):
        """Updates the profile directly from the MCMC results."""
        print("\nUpdating user's long-term profile with rigorous MCMC results...")
        for param_name, stats in posteriors.items():
            parts = param_name.split('_', 1)
            if len(parts) == 2:
                criterion, level = parts
                if criterion not in self.data: self.data[criterion] = {}
                self.data[criterion][level] = {'mean_utility': stats['mean'], 'variance': stats['sd']**2}
            else:
                print(f"Warning: Could not parse MCMC parameter name for profile update: {param_name}")
        self.save()

# --- R/idefix Integration ---
def request_R_design(utilities_model, criteria_levels_dict, mode="modfed_single", num_sets_for_cea=4):
    """
    Prepares data and calls R script for conjoint design generation.
    mode: "modfed_single" or "cea_block"
    num_sets_for_cea: if mode is "cea_block", number of sets to generate.
    Returns: (data, error_message_from_r)
             data: for modfed_single: (profile_a, profile_b)
                   for cea_block: list of {"profile_a":..., "profile_b":...} dicts
             error_message_from_r: string if R reported an error in JSON, else None
    """
    print(f"\n...Requesting R design (mode: {mode}, CEA sets: {num_sets_for_cea if mode == 'cea_block' else 'N/A'})...")
    prior_draws = {}; par_names = []

    for crit, levels_data in utilities_model.items():
        if not levels_data or not isinstance(levels_data, dict) or len(levels_data) < 2:
            continue
        level_names = list(levels_data.keys())
        for level_idx, level_name in enumerate(level_names):
            if level_idx == 0: continue
            param_name = f"{crit}_{level_name}"; par_names.append(param_name)
            utility_params = levels_data.get(level_name, {})
            mean_val = utility_params.get('mean', 0.0)
            variance_val = utility_params.get('variance', 10.0)
            draws = norm.rvs(loc=mean_val, scale=math.sqrt(max(variance_val, 1e-6)), size=500)
            prior_draws[param_name] = draws.tolist()

    if not prior_draws and mode == "modfed_single":
        print("Warning: No parameters with varying levels for R (Modfed mode). Cannot proceed with R call.")
        return None, "Python: No parameters to estimate for R (Modfed)."

    idefix_input = {
        "operation_mode": mode,
        "num_cea_sets": num_sets_for_cea if mode == "cea_block" else 1,
        "levels_per_criterion": [len(v) for v in criteria_levels_dict.values() if v],
        "criteria_levels": criteria_levels_dict,
        "prior_draws": prior_draws if prior_draws else {},
        "par_names": par_names if par_names else []
    }
    with open("design_data.json", "w") as f: json.dump(idefix_input, f)

    try:
        process = subprocess.run(['Rscript', 'idefix_generator.R'], capture_output=True, text=True, check=False)

        r_output_json = None
        if process.stdout:
            try:
                r_output_json = json.loads(process.stdout)
            except json.JSONDecodeError:
                print(f"R script STDOUT was not valid JSON: {process.stdout[:500]}")
                pass

        if process.returncode != 0:
            print(f"ERROR: R script 'idefix_generator.R' failed with exit code {process.returncode}.")
            if r_output_json and "error" in r_output_json:
                return None, r_output_json["error"]
            else:
                return None, f"R script failed. STDERR: {process.stderr[:500]}"

        if r_output_json:
            if "error" in r_output_json:
                return None, r_output_json["error"]

            if mode == "modfed_single":
                return (r_output_json.get('profile_a'), r_output_json.get('profile_b')), None
            elif mode == "cea_block":
                design_block = r_output_json.get('design_block')
                if isinstance(design_block, list) and all(isinstance(pair, dict) and "profile_a" in pair and "profile_b" in pair for pair in design_block):
                    return design_block, None
                else:
                    return None, "R script returned malformed design_block for CEA mode."
            else:
                return None, "Unknown R operation mode requested by Python."
        else:
            return None, "R script succeeded but produced no parsable JSON output."

    except FileNotFoundError:
        return None, "Rscript command not found."
    except Exception as e:
        return None, f"Unexpected error running R script: {e}"


# --- Rigorous MCMC Model (Offline Processing) ---
def run_mcmc_model_in_background(user_profile, session_history, criteria_with_levels):
    try:
        import pymc as pm
        import pytensor.tensor as pt
        print("\n--- Starting background MCMC model fitting with PyMC... ---")
        print(f"Processing {len(session_history)} choices to update user profile.")
    except ImportError:
        print("\n--- PyMC (or Pytensor/Aesara) not installed ---")
        print("Skipping rigorous background model update. User profile not updated with MCMC results.")
        print("To enable this feature, please run: pip install pymc blackjax")
        return

    param_names = []; level_map = {}; ref_levels = {}; idx = 0
    for crit, levels in criteria_with_levels.items():
        if not levels or len(levels) < 2: continue
        base_level = levels[0]; ref_levels[crit] = base_level
        for level in levels[1:]:
            param_name = f"{crit}_{level}"; param_names.append(param_name)
            level_map[param_name] = idx; idx += 1

    if not param_names:
        print("No parameters to estimate in MCMC model (all criteria might have only one effective level). Skipping MCMC.")
        return

    num_params = len(param_names); num_questions = len(session_history)
    X = np.zeros((num_questions, 2, num_params)); Y = np.zeros(num_questions, dtype=int)

    for i, (p_a, p_b, choice_idx) in enumerate(session_history):
        Y[i] = choice_idx
        for crit, level in p_a.items():
            if crit in ref_levels and level != ref_levels[crit]:
                param_key = f"{crit}_{level}"
                if param_key in level_map:
                    X[i, 0, level_map[param_key]] = 1
        for crit, level in p_b.items():
            if crit in ref_levels and level != ref_levels[crit]:
                param_key = f"{crit}_{level}"
                if param_key in level_map:
                    X[i, 1, level_map[param_key]] = 1

    with pm.Model() as conjoint_model:
        prior_means = []
        prior_sds = []
        for p_name in param_names:
            crit_for_prior, level_for_prior = p_name.split('_', 1)
            mean, var = user_profile.get_level_prior(crit_for_prior, level_for_prior)
            prior_means.append(mean)
            prior_sds.append(np.sqrt(max(var, 1e-6)))

        beta_coeffs = pm.Normal("beta", mu=np.array(prior_means), sigma=np.array(prior_sds), shape=num_params)
        utilities = pt.dot(X, beta_coeffs)
        probabilities = pm.math.softmax(utilities, axis=1)
        observed_choices = pm.Categorical("observed_choices", p=probabilities, observed=Y)

        print("Starting MCMC sampling... (This may take a minute)")
        try:
            trace = pm.sample(draws=1000, tune=1000, chains=2, cores=1, progressbar=True)
            print("MCMC sampling complete.")
        except Exception as e:
            print(f"MCMC sampling failed with error: {e}. User profile not updated.")
            return

    summary = pm.summary(trace, var_names=["beta"])
    posteriors = {}
    for i in range(len(param_names)):
        summary_param_name = f"beta[{i}]"
        if summary_param_name in summary.index:
             actual_param_name = param_names[i]
             posteriors[actual_param_name] = {
                 'mean': summary.loc[summary_param_name]['mean'],
                 'sd': summary.loc[summary_param_name]['sd']
             }
        elif param_names[i] in summary.index:
             posteriors[param_names[i]] = {
                 'mean': summary.loc[param_names[i]]['mean'],
                 'sd': summary.loc[param_names[i]]['sd']
             }
        else:
            print(f"Warning: Could not find MCMC summary for parameter: {param_names[i]} (expected {summary_param_name})")

    user_profile.update_profile_with_posteriors(posteriors)
    print("--- Background MCMC model fitting finished. User profile updated. ---")


class LLMInformedPriorGenerator:
    """Uses LLM to generate informed priors based on decision context and user profile."""

    def __init__(self):
        self.model_name = "mistralai_mistral-small-3.1-24b-instruct-2503"

    def get_improved_system_prompt(self):
        return """You are an expert in decision analysis and consumer preferences.

Your task is to predict relative utility values for different levels of criteria based on the decision context.

CRITICAL FORMATTING REQUIREMENTS:
1. Output ONLY a valid JSON object.
2. No markdown code blocks (```), no extra text, no explanations outside the JSON.
3. Use exact criterion and level names as provided in the input for the keys in the "utilities" object.
4. The first level of each criterion is always the reference (baseline = 0), so do NOT include it in the "utilities" output for that criterion.

LEVEL ORDERING REQUIREMENTS:
- Ensure levels follow a logical progression (low to high OR high to low)
- Use clear ordering words like: low/medium/high, poor/good/excellent, less/moderate/more
- Avoid parenthetical explanations or complex conditional phrases
- Keep level descriptions concise and transferable across similar decisions

JSON Structure:
{
    "reasoning": "Brief explanation of your reasoning for the assigned utilities.",
    "utilities": {
        "exact_criterion_name_from_input_1": {
            "exact_level_name_2_from_input": {"mean": number_between_-2_and_2, "confidence": "high/medium/low"},
            "exact_level_name_3_from_input": {"mean": number_between_-2_and_2, "confidence": "high/medium/low"}
        },
        "exact_criterion_name_from_input_2": {
            "exact_level_name_2_from_input": {"mean": number_between_-2_and_2, "confidence": "high/medium/low"},
            "exact_level_name_3_from_input": {"mean": number_between_-2_and_2, "confidence": "high/medium/low"}
        }
    }
}

Guidelines:
- Positive means = generally preferred over the reference level for that criterion.
- Negative means = generally less preferred than the reference level for that criterion.
- 'confidence' reflects your certainty in the 'mean' utility prediction.
- Be realistic about typical preferences in the decision domain.
"""

    def _validate_and_clean_llm_response(self, raw_response, criteria_with_levels):
        if not raw_response or not raw_response.strip():
            print("DEBUG VALIDATE: Raw response is empty.")
            return None

        content = raw_response.strip()

        if content.startswith('```json'):
            content = content[len('```json'):].strip()
            if content.endswith('```'):
                content = content[:-len('```')].strip()
        elif content.startswith('```caution'):
            content = content[len('```caution'):].strip()
            if content.endswith('```'):
                content = content[:-len('```')].strip()
        elif content.startswith('```'):
            lines = content.split('\n')
            if len(lines) > 1 and lines[0].strip().startswith('```'):
                content = '\n'.join(lines[1:])
                if content.strip().endswith('```'):
                    content = content.rsplit('```', 1)[0].strip()

        json_start = content.find('{')
        if json_start == -1:
            print("DEBUG VALIDATE: No '{' found in content.")
            return None

        brace_count = 0
        json_end = -1
        for i, char in enumerate(content[json_start:], json_start):
            if char == '{':
                brace_count += 1
            elif char == '}':
                brace_count -= 1
                if brace_count == 0:
                    json_end = i + 1
                    break

        if json_end == -1:
            print("DEBUG VALIDATE: No matching '}' found for the first '{'. Attempting to repair...")
            remaining_content = content[json_start:]
            open_braces = remaining_content.count('{')
            close_braces = remaining_content.count('}')
            missing_braces = open_braces - close_braces

            if missing_braces > 0:
                print(f"DEBUG VALIDATE: Adding {missing_braces} missing closing brace(s)")
                content = content + ('}' * missing_braces)
                json_content_repaired = content[json_start:]
                brace_count = 0
                json_end = -1
                for i_rep, char_rep in enumerate(json_content_repaired):
                    if char_rep == '{': brace_count +=1
                    elif char_rep == '}': brace_count -=1
                    if brace_count == 0:
                        json_end = json_start + i_rep + 1
                        break

            if json_end == -1:
                print("DEBUG VALIDATE: Still no matching '}' found after repair attempt.")
                return None

        json_content = content[json_start:json_end]

        json_content = re.sub(r',(\s*[}\]])', r'\1', json_content)

        try:
            parsed = json.loads(json_content)
            if not isinstance(parsed, dict): return None
            if 'utilities' not in parsed or not isinstance(parsed['utilities'], dict): return None

            expected_criteria_set = set(criteria_with_levels.keys())
            llm_provided_criteria_set = set(parsed.get('utilities', {}).keys())
            if not llm_provided_criteria_set.issubset(expected_criteria_set):
                print(f"Warning: LLM provided criteria keys {llm_provided_criteria_set} not all in expected {expected_criteria_set}. Filtering.")
                parsed['utilities'] = {k: v for k, v in parsed['utilities'].items() if k in expected_criteria_set}

            print("DEBUG VALIDATE: JSON successfully parsed and basic structure okay.")
            return parsed
        except json.JSONDecodeError as e:
            print(f"DEBUG VALIDATE: JSON decode error: {e}. Problematic JSON: {json_content[:500]}")
            return None

    def generate_contextual_priors(self, goal, real_choices, criteria_with_levels, user_context=""):
        print("\n--- Generating LLM-Informed Priors ---")
        real_choices_str = ", ".join([choice['name'] for choice in real_choices])
        criteria_summary = []
        for crit, levels in criteria_with_levels.items():
            levels_str = ", ".join([f'"{l}"' for l in levels])
            criteria_summary.append(f'"{crit}": [{levels_str}]')
        criteria_text = "{ " + ", ".join(criteria_summary) + " }"

        system_prompt = self.get_improved_system_prompt()
        user_prompt = f"Decision Goal: {goal}\nReal Choices: {real_choices_str}\nCriteria and Levels (first level is reference for each):\n{criteria_text}"
        if user_context: user_prompt += f"\nUser Context/Preferences: {user_context}"
        user_prompt += "\n\nBased on all the above, provide your utility predictions in the specified JSON format."

        parsed_response = None
        avg_variance_of_llm_priors = 10.0
        prior_quality_indicator = "heuristic_fallback"

        for attempt in range(2):
            temperature = 0.2 if attempt == 0 else 0.0
            print(f"LLM Prior Gen Attempt {attempt+1}/2 with temperature {temperature}...")
            raw_response = llm_call(system_prompt, user_prompt, self.model_name, temperature=temperature)
            if raw_response:
                parsed_response = self._validate_and_clean_llm_response(raw_response, criteria_with_levels)
                if parsed_response and 'utilities' in parsed_response: break
            if not parsed_response and attempt < 1 : print("LLM response parsing failed or 'utilities' key missing, retrying...")

        if not parsed_response or 'utilities' not in parsed_response:
            print("Failed to get valid LLM response for priors. Using default heuristic priors.")
            return self._generate_default_informed_priors(criteria_with_levels), "heuristic_fallback", avg_prior_variance

        print(f"LLM Reasoning for Priors: {parsed_response.get('reasoning', 'No reasoning provided')}")
        informed_utilities = {}; variances_collected = []
        llm_utilities_data = parsed_response.get('utilities', {})
        all_confidences = []
        heuristic_used_for_any_level = False

        for crit, levels in criteria_with_levels.items():
            informed_utilities[crit] = {}
            for i, level in enumerate(levels):
                if i == 0:
                    informed_utilities[crit][level] = {'mean': 0.0, 'variance': 0.1}
                else:
                    llm_level_util = llm_utilities_data.get(crit, {}).get(level, {})
                    if llm_level_util and 'mean' in llm_level_util and 'confidence' in llm_level_util:
                        mean_val = float(llm_level_util['mean'])
                        confidence = llm_level_util.get('confidence', 'medium').lower()
                        if confidence not in ['high', 'medium', 'low']: confidence = 'medium'
                        all_confidences.append(confidence)
                        variance_map = {'high': 0.5, 'medium': 1.5, 'low': 3.0}
                        variance_val = variance_map.get(confidence)
                        variances_collected.append(variance_val)
                    else:
                        print(f"LLM did not provide specific prior for '{crit}' - '{level}'. Using heuristic.")
                        mean_val = self._heuristic_utility_by_position(i, len(levels))
                        variance_val = 2.5
                        heuristic_used_for_any_level = True
                        variances_collected.append(variance_val)
                    informed_utilities[crit][level] = {'mean': mean_val, 'variance': variance_val}

        if heuristic_used_for_any_level:
            prior_quality_indicator = "llm_success_mixed_with_heuristics"
        elif all_confidences:
            if all(c == 'high' for c in all_confidences): prior_quality_indicator = "llm_success_high_confidence"
            elif all(c == 'low' for c in all_confidences): prior_quality_indicator = "llm_success_low_confidence"
            else: prior_quality_indicator = "llm_success_medium_confidence"
        else:
            prior_quality_indicator = "llm_success_parsing_issues"

        avg_variance_of_llm_priors = sum(variances_collected) / len(variances_collected) if variances_collected else 10.0
        print(f"Successfully generated LLM-informed priors. Quality: {prior_quality_indicator}, Avg Variance: {avg_variance_of_llm_priors:.2f}")
        return informed_utilities, prior_quality_indicator, avg_variance_of_llm_priors

    def _heuristic_utility_by_position(self, position, total_levels):
        if total_levels == 3:
            if position == 1: return 0.3
            if position == 2: return -0.1
        elif total_levels == 2:
             if position == 1: return 0.2
        return 0.1 * (position)

    def _generate_default_informed_priors(self, criteria_with_levels):
        utilities = {}
        for crit, levels in criteria_with_levels.items():
            utilities[crit] = {}
            for i, level in enumerate(levels):
                if i == 0:
                    utilities[crit][level] = {'mean': 0.0, 'variance': 0.1}
                else:
                    mean_val = self._heuristic_utility_by_position(i, len(levels))
                    utilities[crit][level] = {'mean': mean_val, 'variance': 3.0}
        return utilities

    def get_user_preference_hints(self, goal, criteria_with_levels):
        """Collect additional user context to inform priors, focusing on criteria importance."""
        print("\n--- Optional: Provide Preference Hints for Initial Setup ---")

        print("Examples of hints (you can mention your specific criteria or levels):")

        crit_keys = list(criteria_with_levels.keys())
        example_crit1 = crit_keys[0] if len(crit_keys) > 0 else "[Your First Criterion]"
        example_crit2 = crit_keys[1] if len(crit_keys) > 1 else "[Your Second Criterion]"
        example_crit3 = crit_keys[2] if len(crit_keys) > 2 else "[Your Third Criterion]"

        print(f"- '{example_crit1} is a deal breaker.'")
        print(f"- 'I care most about {example_crit1} and then {example_crit2}.'")
        print(f"- '{example_crit3} is not very important to me for this decision.'")

        hint = input("Please provide any hints about what criteria are most important to you for this decision (or press Enter to skip): ").strip()
        return hint


class BayesianUBC_Engine:
    def __init__(self, criteria_with_levels, user_profile_manager, informed_priors=None,
                 initial_strategy="PYTHON_ORTHOGONAL_CF", initial_block_size_cea=4):
        self.criteria_levels = criteria_with_levels
        self.user_profile = user_profile_manager
        self.utilities = {}

        self.orthogonal_fallback_pair_idx_counter = 0
        self.L9_PROFILES = [[0,0,0], [0,1,1], [0,2,2], [1,0,1], [1,1,2], [1,2,0], [2,0,2], [2,1,0], [2,2,1]]
        self.ORTHOGONAL_PAIRS_INDICES = [(0,4), (1,5), (2,6), (3,8), (0,7), (1,3), (2,5), (4,8), (6,0)]
        self.MAX_ORTHOGONAL_FALLBACK_TOTAL = len(self.ORTHOGONAL_PAIRS_INDICES)

        self.initial_strategy = initial_strategy
        self.initial_block_size_cea = initial_block_size_cea
        self.MAX_PYTHON_ORTHO_CF_QUESTIONS = 3

        self.initial_questions_done = 0
        self.cea_block_cache = []
        self.cea_block_cache_idx = 0

        if informed_priors:
            print("\nInitializing conjoint engine with LLM-informed priors.")
            self.utilities = informed_priors
        else:
            print("\nInitializing conjoint engine with default/profile priors.")
            for crit, levels in self.criteria_levels.items():
                self.utilities[crit] = {};
                if levels:
                    for level in levels:
                        mean, var = self.user_profile.get_level_prior(crit, level)
                        self.utilities[crit][level] = {'mean': float(mean), 'variance': float(var)}
                else:
                    self.utilities[crit] = {}

        self.question_count = 0
        self.session_history = []
        self.r_idefix_consecutive_failures = 0

    def _get_python_orthogonal_pair(self):
        """Gets the next predefined orthogonal pair from Python's L9 definition."""
        if not self.criteria_levels or len(self.criteria_levels) != 3:
            print("ERROR (Orthogonal): Criteria not set up for 3x3 design.")
            return None, None

        pair_definition_indices = self.ORTHOGONAL_PAIRS_INDICES[self.orthogonal_fallback_pair_idx_counter % self.MAX_ORTHOGONAL_FALLBACK_TOTAL]

        profile_a_indices = self.L9_PROFILES[pair_definition_indices[0]]
        profile_b_indices = self.L9_PROFILES[pair_definition_indices[1]]

        profile_a, profile_b = {}, {}
        crit_names = list(self.criteria_levels.keys())

        for i, crit_name in enumerate(crit_names):
            levels_for_crit = self.criteria_levels[crit_name]
            if len(levels_for_crit) != 3:
                print(f"ERROR (Orthogonal): Criterion '{crit_name}' does not have 3 levels.")
                return None, None
            profile_a[crit_name] = levels_for_crit[profile_a_indices[i]]
            profile_b[crit_name] = levels_for_crit[profile_b_indices[i]]

        self.orthogonal_fallback_pair_idx_counter += 1
        return profile_a, profile_b

    def run_elicitation_session(self):
        max_questions = 8

        for _ in range(max_questions):
            self.question_count += 1
            print(f"\n--- Generating Question {self.question_count}/{max_questions} ---")

            profile_a, profile_b = None, None
            perform_counterfactual_this_turn = False

            current_phase_strategy = self.initial_strategy
            if self.initial_strategy == "PYTHON_ORTHOGONAL_CF" and self.initial_questions_done >= self.MAX_PYTHON_ORTHO_CF_QUESTIONS:
                current_phase_strategy = "MODFED_ADAPTIVE"
            elif self.initial_strategy == "CEA_BLOCK_CF" and self.initial_questions_done >= self.initial_block_size_cea:
                current_phase_strategy = "MODFED_ADAPTIVE"

            print(f"DEBUG ENGINE: Current phase strategy: {current_phase_strategy}, Initial Qs done: {self.initial_questions_done}")

            if current_phase_strategy == "PYTHON_ORTHOGONAL_CF":
                print(f"Kickstart Phase (Python Ortho+CF): Question {self.initial_questions_done + 1}/{self.MAX_PYTHON_ORTHO_CF_QUESTIONS}.")
                profile_a, profile_b = self._get_python_orthogonal_pair()
                perform_counterfactual_this_turn = True
                self.initial_questions_done += 1
                if profile_a is None: break

            elif current_phase_strategy == "CEA_BLOCK_CF":
                print(f"Kickstart Phase (CEA Block+CF): Question {self.initial_questions_done + 1}/{self.initial_block_size_cea}.")
                if not self.cea_block_cache or self.cea_block_cache_idx >= len(self.cea_block_cache):
                    print("Fetching new CEA block from R...")
                    r_data, r_error = request_R_design(self.utilities, self.criteria_levels, mode="cea_block", num_sets_for_cea=self.initial_block_size_cea)
                    if r_error or not r_data or not isinstance(r_data, list):
                        print(f"Failed to get CEA block from R: {r_error}. Falling back to Python Orthogonal + CF for this turn.")
                        profile_a, profile_b = self._get_python_orthogonal_pair()
                        perform_counterfactual_this_turn = True
                        self.initial_strategy = "PYTHON_ORTHOGONAL_CF"
                        self.MAX_PYTHON_ORTHO_CF_QUESTIONS = self.initial_questions_done + 1
                        self.initial_questions_done = 0
                    else:
                        self.cea_block_cache = r_data
                        self.cea_block_cache_idx = 0

                if self.cea_block_cache and self.cea_block_cache_idx < len(self.cea_block_cache):
                    pair_dict = self.cea_block_cache[self.cea_block_cache_idx]
                    profile_a = pair_dict.get("profile_a")
                    profile_b = pair_dict.get("profile_b")
                    self.cea_block_cache_idx +=1
                    perform_counterfactual_this_turn = True
                else:
                    print("Error using CEA block cache or cache empty. Falling back to Python Orthogonal + CF for this turn.")
                    profile_a, profile_b = self._get_python_orthogonal_pair()
                    perform_counterfactual_this_turn = True
                self.initial_questions_done += 1
                if profile_a is None: break

            else: # MODFED_DIRECT or MODFED_ADAPTIVE phase
                if current_phase_strategy == "MODFED_DIRECT" and self.initial_questions_done == 0:
                     print("Attempting direct Modfed question generation (strong priors assumed).")
                elif self.initial_questions_done > 0 and self.initial_strategy != "MODFED_DIRECT" and current_phase_strategy == "MODFED_ADAPTIVE" and self.r_idefix_consecutive_failures == 0 :
                     print("\n--- Initial kickstart phase complete. Switching to adaptive questions (Modfed). ---")
                if current_phase_strategy == "MODFED_DIRECT" or (self.initial_strategy != "MODFED_DIRECT" and current_phase_strategy == "MODFED_ADAPTIVE"):
                    self.initial_questions_done +=1

                r_data_tuple, r_error = request_R_design(self.utilities, self.criteria_levels, mode="modfed_single")

                if r_error or not r_data_tuple:
                    print(f"idefix::Modfed failed: {r_error}. Falling back to simple Python Orthogonal question (no CF).")
                    profile_a, profile_b = self._get_python_orthogonal_pair()
                    self.r_idefix_consecutive_failures += 1
                    if profile_a is None: break
                    if self.r_idefix_consecutive_failures >= 3:
                        print("\nWARNING: Optimal question generation (idefix) has struggled multiple times after kickstart.")
                        print("The session will continue with orthogonal questions, but learning may be less efficient.\n")
                else:
                    profile_a, profile_b = r_data_tuple
                    self.r_idefix_consecutive_failures = 0

            if profile_a is None or profile_b is None:
                print("Could not generate a valid question. Ending preference elicitation session.")
                break

            header = f"| {'Feature':<25} | {'Option A':<35} | {'Option B':<35} |"; separator = "-" * len(header)
            print(f"\nQuestion {self.question_count}: Which option would you choose?")
            print(separator); print(header); print(separator)
            crit_keys_ordered = list(self.criteria_levels.keys())
            for crit_key in crit_keys_ordered:
                val_a = profile_a.get(crit_key, 'N/A')
                val_b = profile_b.get(crit_key, 'N/A')
                print(f"| {str(crit_key):<25} | {str(val_a):<35} | {str(val_b):<35} |")
            print(separator)

            user_main_choice_input = ""
            while user_main_choice_input not in ['1', '2']:
                user_main_choice_input = input("Your preference (1 for Option A, 2 for Option B): ").strip()

            chosen_idx_main = 0 if user_main_choice_input == '1' else 1
            chosen_profile_main = profile_a if chosen_idx_main == 0 else profile_b
            rejected_profile_main = profile_b if chosen_idx_main == 0 else profile_a

            self.session_history.append((profile_a, profile_b, chosen_idx_main))
            self._update_utilities_simplified(chosen_profile_main, rejected_profile_main)

            if perform_counterfactual_this_turn:
                self._ask_and_process_counterfactual(chosen_profile_main, rejected_profile_main)

            if self._check_convergence():
                print(f"\n...Model has converged (heuristically) after {self.question_count} questions...")
                break
        return self.utilities, self.session_history

    def _find_most_uncertain_level(self, profile_to_consider):
        """Finds a (criterion, level_name, current_level_idx) on the given profile to target for counterfactual.
           Prioritizes non-reference levels with high variance.
           Returns None if no suitable target found.
        """
        uncertain_levels = []
        for crit, levels_data in self.utilities.items():
            if crit not in profile_to_consider: continue

            actual_level_on_profile = profile_to_consider[crit]
            crit_defined_levels = self.criteria_levels.get(crit, [])
            if not crit_defined_levels: continue

            try:
                level_idx_on_profile = crit_defined_levels.index(actual_level_on_profile)
            except ValueError:
                continue

            util_info = levels_data.get(actual_level_on_profile)
            if util_info:
                uncertain_levels.append((util_info.get('variance', 10.0), crit, actual_level_on_profile, level_idx_on_profile))

        if not uncertain_levels:
            if list(profile_to_consider.keys()):
                crit = list(profile_to_consider.keys())[0]
                lvl_name = profile_to_consider[crit]
                try:
                    lvl_idx = self.criteria_levels.get(crit,[]).index(lvl_name)
                    return crit, lvl_name, lvl_idx
                except ValueError: return None, None, -1
            return None, None, -1

        uncertain_levels.sort(key=lambda x: x[0], reverse=True)
        return uncertain_levels[0][1], uncertain_levels[0][2], uncertain_levels[0][3]


    def _ask_and_process_counterfactual(self, chosen_profile_main, rejected_profile_main):
        kickstart_max_display = self.MAX_PYTHON_ORTHO_CF_QUESTIONS if self.initial_strategy == 'PYTHON_ORTHOGONAL_CF' else self.initial_block_size_cea
        print(f"\n--- Follow-up Counterfactual Question ({self.initial_questions_done}/{kickstart_max_display}) ---")

        target_criterion, target_level_original_rejected, original_level_idx = self._find_most_uncertain_level(rejected_profile_main)

        if not target_criterion:
            if list(rejected_profile_main.keys()):
                target_criterion = list(rejected_profile_main.keys())[0]
                target_level_original_rejected = rejected_profile_main[target_criterion]
                try:
                    original_level_idx = self.criteria_levels.get(target_criterion, []).index(target_level_original_rejected)
                except ValueError:
                    print("Counterfactual: Fallback failed - original level not in defined list. Skipping.")
                    return
            else:
                print("Counterfactual: Rejected profile is empty. Skipping.")
                return

        levels_for_criterion = self.criteria_levels.get(target_criterion)
        if not levels_for_criterion or len(levels_for_criterion) != 3:
            print(f"Counterfactual: Levels for '{target_criterion}' not properly defined (requires 3). Skipping.")
            return

        improved_level_idx = -1
        if original_level_idx == 0: improved_level_idx = 1
        elif original_level_idx == 1: improved_level_idx = 2
        elif original_level_idx == 2: improved_level_idx = 1

        if not (0 <= improved_level_idx < len(levels_for_criterion)) or improved_level_idx == original_level_idx:
            for i in range(len(levels_for_criterion)):
                if i != original_level_idx:
                    improved_level_idx = i
                    break
            if improved_level_idx == original_level_idx:
                 print(f"Counterfactual: Cannot find a distinct alternative level for '{target_criterion}'. Skipping.")
                 return

        improved_level = levels_for_criterion[improved_level_idx]

        modified_rejected_profile = rejected_profile_main.copy()
        modified_rejected_profile[target_criterion] = improved_level

        if modified_rejected_profile == chosen_profile_main:
            print("Counterfactual: Modified rejected profile became identical to chosen profile. Skipping.")
            return

        print(f"You initially chose the profile on the right (Your Original Choice B).")
        print(f"Now, consider if the other option (originally rejected and shown as Modified Option A) was changed:")

        header = f"| {'Feature':<25} | {'Modified Option A':<50} | {'Your Original Choice B':<35} |"
        separator = "-" * len(header)
        print(separator)
        print(header)
        print(separator)

        crit_keys_ordered = list(self.criteria_levels.keys())
        for crit_key in crit_keys_ordered:
            val_modified_a = modified_rejected_profile.get(crit_key, 'N/A')
            val_original_b = chosen_profile_main.get(crit_key, 'N/A')

            val_modified_a_display = str(val_modified_a)
            if crit_key == target_criterion:
                val_modified_a_display = f"**{str(val_modified_a)}** (changed from '{target_level_original_rejected}')"

            print(f"| {str(crit_key):<25} | {val_modified_a_display:<50} | {str(val_original_b):<35} |")
        print(separator)

        cf_choice_input = ""
        while cf_choice_input not in ['1', '2']:
            cf_choice_input = input("Which do you now prefer? (1 for Modified Option A, 2 for Your Original Choice B): ").strip()

        user_switched = (cf_choice_input == '1')

        self._update_utilities_from_counterfactual(
            chosen_profile_main,
            rejected_profile_main,
            modified_rejected_profile,
            target_criterion,
            target_level_original_rejected,
            improved_level,
            user_switched
        )

    def _update_utilities_from_counterfactual(self, chosen_profile_main,
                                             original_rejected_profile, modified_rejected_profile,
                                             criterion_changed, original_level_on_rejected,
                                             improved_level_on_modified, user_switched):

        print(f"DEBUG CF Update: Initial choice: Preferred a profile over one with {criterion_changed}='{original_level_on_rejected}'.")
        print(f"DEBUG CF Update: Counterfactual offered {criterion_changed}='{improved_level_on_modified}'. User switched: {user_switched}")

        if criterion_changed not in self.utilities or \
           original_level_on_rejected not in self.utilities[criterion_changed] or \
           improved_level_on_modified not in self.utilities[criterion_changed]:
            print(f"DEBUG CF Update: ERROR - Level data missing for {criterion_changed}. Cannot update.")
            return

        util_original_level = self.utilities[criterion_changed][original_level_on_rejected]
        util_improved_level = self.utilities[criterion_changed][improved_level_on_modified]

        if user_switched:
            print(f"  User switched. This means the change from '{original_level_on_rejected}' to '{improved_level_on_modified}' was significant.")
            util_improved_level['mean'] = min(2.0, util_improved_level['mean'] + 0.5)
            util_improved_level['variance'] = max(0.01, util_improved_level['variance'] * 0.6)
            util_original_level['mean'] = max(-2.0, util_original_level['mean'] - 0.3)
            util_original_level['variance'] = max(0.01, util_original_level['variance'] * 0.8)
        else:
            print(f"  User did not switch. The improvement to '{improved_level_on_modified}' was not sufficient.")
            util_improved_level['mean'] = max(-2.0, util_improved_level['mean'] - 0.2)
            util_improved_level['variance'] = max(0.01, util_improved_level['variance'] * 0.7)
            util_original_level['mean'] = max(-2.0, util_original_level['mean'] - 0.1)

        for crit_data_util in self.utilities.values():
            for level_data_util in crit_data_util.values():
                level_data_util['mean'] = max(-2.0, min(2.0, level_data_util['mean']))
                level_data_util['variance'] = max(0.01, level_data_util['variance'])

        print(f"DEBUG CF Update: Utilities for '{criterion_changed}': Original '{original_level_on_rejected}' now {util_original_level}, Improved '{improved_level_on_modified}' now {util_improved_level}")


    def _update_utilities_simplified(self, chosen, unchosen):
        for crit, lvl in chosen.items():
            if crit in self.utilities and lvl in self.utilities[crit]:
                self.utilities[crit][lvl]['mean'] += 0.2
                self.utilities[crit][lvl]['variance'] *= 0.9
                self.utilities[crit][lvl]['variance'] = max(0.01, self.utilities[crit][lvl]['variance'])
        for crit, lvl in unchosen.items():
            if crit in chosen and chosen.get(crit) != lvl:
                 if crit in self.utilities and lvl in self.utilities[crit]:
                    self.utilities[crit][lvl]['mean'] -= 0.1
                    self.utilities[crit][lvl]['variance'] *= 0.95
                    self.utilities[crit][lvl]['variance'] = max(0.01, self.utilities[crit][lvl]['variance'])

    def _check_convergence(self):
        all_level_data = [lvl_data for crit_data in self.utilities.values() if crit_data for lvl_data in crit_data.values()]
        if not all_level_data: return False
        num_levels = len(all_level_data)
        if num_levels == 0: return False
        total_variance = sum(lvl_data['variance'] for lvl_data in all_level_data)
        avg_variance = total_variance / num_levels
        print(f"(Current model uncertainty (avg utility variance): {avg_variance:.3f})")
        convergence_threshold = 1.0
        return avg_variance < convergence_threshold


# --- Main Application Flow ---
class DecisionApp:
    def __init__(self, user_id="default_user"):
        self.user_profile = UserProfileHBM(user_id)
        self.prior_generator = LLMInformedPriorGenerator()

    def run(self):
        goal, real_choices = self._get_initial_input()
        if not goal or not real_choices:
            print("Goal and at least one real choice must be provided. Exiting.")
            return

        safety_check_result = self._perform_safety_check(goal, real_choices)
        if not safety_check_result:
            return

        criteria_with_levels = self._get_criteria_and_levels(goal, real_choices)

        if not criteria_with_levels or \
           not all(isinstance(levels, list) and len(levels) == 3 for levels in criteria_with_levels.values()) or \
           len(criteria_with_levels) != 3:
            print("Criteria and levels definition was not completed successfully (each of the 3 criteria must have exactly 3 levels). Exiting.")
            return

        user_preference_hints = self.prior_generator.get_user_preference_hints(goal, criteria_with_levels) # Pass necessary context

        informed_priors = None
        prior_quality_indicator = "heuristic_fallback"
        avg_prior_variance = 10.0

        print("\n--- Initializing Preference Assumptions ---")
        print("Using AI to set smarter starting preference assumptions based on your goal and context...")

        priors_data_tuple = self.prior_generator.generate_contextual_priors(
            goal, real_choices, criteria_with_levels, user_preference_hints
        )
        if isinstance(priors_data_tuple, tuple) and len(priors_data_tuple) == 3:
            informed_priors, prior_quality_indicator, avg_prior_variance = priors_data_tuple
        else:
            print("Warning: Prior generator did not return expected data structure. Using default heuristic priors.")
            informed_priors = self.prior_generator._generate_default_informed_priors(criteria_with_levels)

        if informed_priors:
            print("\nInitial AI-suggested preference assumptions (you can override these through your choices):")
            for crit, levels_dict in informed_priors.items():
                print(f"\n  For '{crit}':")
                original_levels_for_crit = criteria_with_levels.get(crit, [])
                for level_name in original_levels_for_crit:
                    if level_name in levels_dict:
                        util_info = levels_dict[level_name]
                        mean_val = util_info['mean']
                        preference_desc = "neutral"
                        if mean_val > 0.1: preference_desc = "generally preferred"
                        elif mean_val < -0.1: preference_desc = "generally less preferred"
                        is_reference = any(lvl == level_name and idx == 0 for idx, lvl in enumerate(original_levels_for_crit))
                        ref_text = " (baseline)" if is_reference and mean_val == 0 else ""
                        print(f"    - {level_name}: {preference_desc}{ref_text} (AI initial utility: {mean_val:.2f}, variance: {util_info['variance']:.2f})")
                    else:
                            print(f"    - {level_name}: (No specific prior from LLM, will use default/profile)")

        initial_strategy = "MODFED_DIRECT"
        initial_block_size_cea = 0

        VERY_DIFFUSE_VARIANCE_THRESHOLD = 2.0
        STRONG_PRIOR_VARIANCE_THRESHOLD = 0.75

        if prior_quality_indicator == "heuristic_fallback" or avg_prior_variance > VERY_DIFFUSE_VARIANCE_THRESHOLD:
            initial_strategy = "PYTHON_ORTHOGONAL_CF"
            print("\nPRIOR ASSESSMENT: Priors are diffuse. Starting with Orthogonal + Counterfactual questions.")
        elif avg_prior_variance <= STRONG_PRIOR_VARIANCE_THRESHOLD and "high_confidence" in prior_quality_indicator :
            initial_strategy = "MODFED_DIRECT"
            print("\nPRIOR ASSESSMENT: Priors seem strong. Attempting direct adaptive questions (Modfed).")
        else:
            initial_strategy = "CEA_BLOCK_CF"
            initial_block_size_cea = 4
            print(f"\nPRIOR ASSESSMENT: Priors are somewhat diffuse. Starting with a {initial_block_size_cea}-question CEA block + Counterfactuals.")
            print("NOTE: This requires your R script (idefix_generator.R) to support 'cea_block' mode.")


        engine = BayesianUBC_Engine(criteria_with_levels, self.user_profile,
                                    informed_priors=informed_priors,
                                    initial_strategy=initial_strategy,
                                    initial_block_size_cea=initial_block_size_cea)
        final_session_utilities, session_history = engine.run_elicitation_session()

        if not final_session_utilities:
            print("Preference elicitation did not complete successfully. Cannot provide recommendation.")
        else:
            self._provide_recommendation(real_choices, criteria_with_levels, final_session_utilities)

        if session_history:
             run_mcmc_model_in_background(self.user_profile, session_history, criteria_with_levels)
        else:
            print("\nNo session data recorded, skipping background model update.")
        print("\n--- Decision Process Complete ---")

    def _parse_safety_check_response(self, raw_response):
        if not raw_response or not raw_response.strip():
            print("DEBUG SAFETY PARSER: Raw response is empty.")
            return None
        content = raw_response.strip()

        if content.startswith('```json'):
            content = content[len('```json'):].strip()
            if content.endswith('```'):
                content = content[:-len('```')].strip()
        elif content.startswith('```'):
            lines = content.split('\n')
            if len(lines) > 0 and lines[0].strip().startswith('```'):
                content = '\n'.join(lines[1:])
            if content.strip().endswith('```'):
                content = content.rsplit('```', 1)[0].strip()

        try:
            json_start = content.find('{')
            json_end = content.rfind('}')
            if json_start != -1 and json_end != -1 and json_end > json_start:
                json_str = content[json_start : json_end + 1]
                parsed = json.loads(json_str)
                if isinstance(parsed, dict) and 'is_safe' in parsed:
                    print("DEBUG SAFETY PARSER: Successfully parsed safety check response.")
                    return parsed
                else:
                    print("DEBUG SAFETY PARSER: Parsed JSON does not have 'is_safe' key or is not a dict.")
            else:
                print("DEBUG SAFETY PARSER: Could not find valid JSON object boundaries.")
            return None
        except json.JSONDecodeError as e:
            print(f"DEBUG SAFETY PARSER: JSON decode error: {e}. Response: {content[:200]}")
            return None

    def _perform_safety_check(self, goal, real_choices):
        print("\n--- Performing Initial Safety Check ---")
        real_choices_str = ", ".join([choice['name'] for choice in real_choices])

        safety_system_prompt = """You are a content moderation assistant. Analyze the user's decision goal and choices.
Respond ONLY with a valid JSON object with the following structure:
{"is_safe": boolean, "reason": "string (optional, if unsafe)"}
`is_safe` should be false if the topic involves suicide, self-harm, physical harm to others, murder, explicitly illegal activities (e.g., drug manufacturing, theft, hacking for malicious purposes), or promoting hate speech/discrimination.
Otherwise, `is_safe` should be true. Do not include any other text or markdown formatting outside of this JSON object."""

        safety_user_prompt = f"Decision Goal: '{goal}'. Real Choices: '{real_choices_str}'."

        raw_safety_response = llm_call(safety_system_prompt, safety_user_prompt,
                                       model_name="mistralai_mistral-small-3.1-24b-instruct-2503",
                                       temperature=0.1)

        if not raw_safety_response:
            print("Warning: Could not get a response from the safety check LLM. Proceeding with caution.")
            return True

        safety_assessment = self._parse_safety_check_response(raw_safety_response)

        if safety_assessment and isinstance(safety_assessment.get("is_safe"), bool):
            if not safety_assessment["is_safe"]:
                reason = safety_assessment.get("reason", "No specific reason provided by AI.")
                print(f"\nI can't help you with that topic. Reason: {reason}")
                return False
            else:
                print("Safety check passed. Proceeding with decision process.")
                return True
        else:
            print("Warning: Could not reliably parse the safety assessment from the LLM. Proceeding with caution.")
            print(f"LLM Raw Safety Response: {raw_safety_response}")
            return True


    def _get_initial_input(self):
        print("--- Step 1: Define Your Decision ---");
        goal = ""
        while not goal:
            goal = input("What is the decision you want to make? (e.g., 'Choose a new car'): ").strip()
            if not goal:
                print("Decision goal cannot be empty. Please enter your decision goal.")

        choices_input = []
        min_choices = 2
        while len(choices_input) < min_choices:
            prompt_msg = f"Enter name for REAL Choice {len(choices_input) + 1}"
            if len(choices_input) >= min_choices:
                prompt_msg += f" (or press Enter to finish if >= {min_choices} choices): "
            else:
                prompt_msg += f" (at least {min_choices} required): "

            choice_name = input(prompt_msg).strip()
            if not choice_name:
                if len(choices_input) >= min_choices:
                    break
                else:
                    print(f"Please enter at least {min_choices} real choices.")
                    continue
            choices_input.append({"name": choice_name, "attributes": {}})
        return goal, choices_input

    def _parse_levels_from_llm_text(self, llm_text_output, expected_criteria_names):
        print(f"DEBUG PARSER: Starting to parse levels. Expected criteria: {expected_criteria_names}")
        parsed_levels = {}
        if not llm_text_output:
            print("DEBUG PARSER: LLM text output is empty.")
            return parsed_levels

        normalized_expected_criteria = {
            re.sub(r'\s*\(.*?\)', '', name).strip().lower(): name
            for name in expected_criteria_names
        }
        expected_keys = list(normalized_expected_criteria.keys())

        current_criterion_original_name = None
        levels_parsed_for_current = 0

        lines = llm_text_output.strip().split('\n')

        for i, line_content in enumerate(lines):
            line = line_content.strip()
            print(f"DEBUG PARSER: Processing line {i+1}/{len(lines)}: '{line}' (repr: {repr(line)}) (Active criterion: {current_criterion_original_name}, Levels parsed for current: {levels_parsed_for_current})")

            if not line:
                print("DEBUG PARSER: Blank line found.")
                continue

            header_match = re.search(r'\*\*(.+?):\*\*', line)
            is_criterion_header_this_line = False

            if header_match:
                potential_name_from_header = header_match.group(1).strip().rstrip(':').strip()
                print(f"DEBUG PARSER: Line matches specific header regex. Potential name extracted: '{potential_name_from_header}'")

                normalized_potential_name = re.sub(r'\s*\(.*?\)', '', potential_name_from_header).strip().lower()

                closest_matches = difflib.get_close_matches(normalized_potential_name, expected_keys, n=1, cutoff=0.6)

                if closest_matches:
                    matched_key_normalized = closest_matches[0]
                    current_criterion_original_name = normalized_expected_criteria[matched_key_normalized]

                    if current_criterion_original_name not in parsed_levels:
                        parsed_levels[current_criterion_original_name] = []
                    levels_parsed_for_current = 0